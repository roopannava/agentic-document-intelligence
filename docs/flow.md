# Agentic pipeline flow — `/api/agentic/process-pdf`

The multi-agent document-intelligence flow: the lead orchestrator uploads the PDF to S3,
then drives a `stop_reason` loop that delegates to **Amazon Textract** (deterministic tool)
and two **Claude subagents** (categorize, research), and finally summarizes.

![Agentic pipeline sequence diagram](flow-sequence.png)

> To render in mermaid.live / mermaid.ai, paste **only** the lines of ONE diagram below —
> start at `sequenceDiagram` or `flowchart TD`, and do **not** include the ```` ```mermaid ````
> fence lines or anything else from this file.

## 1. Sequence diagram — the full flow

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant Ctrl as AgenticController
    participant Lead as LeadOrchestratorAgent
    participant S3 as Amazon S3
    participant TX as Amazon Textract
    participant CL as Claude Lead
    participant CAT as Claude Categorizer
    participant RES as Claude Research

    Client->>Ctrl: POST /api/agentic/process-pdf
    Ctrl->>Lead: runFromPdf bytes name
    Note over Lead,S3: 1. store the PDF (push)
    Lead->>S3: storage.upload bytes
    S3-->>Lead: s3 key
    Note over Lead,RES: 2. agentic loop - Claude decides via stop_reason
    Lead->>CL: messages.create system + tools + conversation
    CL-->>Lead: stop_reason tool_use - extract_transactions
    Lead->>TX: textract.extract key
    TX->>S3: read PDF from S3 (pull)
    S3-->>TX: PDF
    TX-->>Lead: rows
    Lead->>CL: messages.create + tool_result rows
    CL-->>Lead: stop_reason tool_use - categorize_transactions
    Lead->>CAT: categorize rows
    CAT-->>Lead: categorized JSON
    Lead->>CL: messages.create + tool_result categories
    CL-->>Lead: stop_reason tool_use - research_merchant
    Lead->>RES: research merchant
    RES-->>Lead: likely category + reason
    Lead->>CL: messages.create + tool_result finding
    CL-->>Lead: stop_reason end_turn - final summary
    Note over Lead,S3: 3. cleanup (finally)
    Lead->>S3: storage.delete key
    Lead-->>Ctrl: AgenticResult finalAnswer trace
    Ctrl-->>Client: JSON finalAnswer + trace
```

## 2. Flowchart — the `stop_reason` decision loop

![stop_reason decision loop](flow-loop.png)

```mermaid
flowchart TD
    A[POST /process-pdf with a PDF] --> B[AgenticController.processPdf]
    B --> C[LeadOrchestratorAgent.runFromPdf]
    C --> D[storage.upload bytes to S3 returns key]
    D --> E[Agentic loop - call Claude Lead]
    E --> F{stop_reason}
    F -->|tool_use extract_transactions| G[textract.extract key - Textract pulls PDF from S3]
    G --> H[holder equals rows]
    H --> E
    F -->|tool_use categorize_transactions| I[Categorizer subagent categorize rows]
    I --> E
    F -->|tool_use research_merchant| J[Research subagent research merchant]
    J --> E
    F -->|end_turn| K[Lead writes final summary]
    K --> L[storage.delete key in finally]
    L --> M[return AgenticResult to client]
```

## Boundaries (which calls leave the JVM)

| Call | Leaves the JVM to | Direction |
|---|---|---|
| `storage.upload(bytes)` | AWS → S3 | you **push** the PDF in |
| `textract.extract(key)` | AWS → Textract | Textract **pulls** the PDF from S3 itself |
| `client.messages().create(...)` | Anthropic → Claude (lead) | the orchestrator's decisions |
| `categorizer.categorize(rows)` | Anthropic → Claude (subagent) | the call that categorizes |
| `research.research(...)` | Anthropic → Claude (subagent) | resolve cryptic merchants |
| `storage.delete(key)` | AWS → S3 | remove the PDF |

## The two kinds of tool
- **Deterministic tool** — `extract_transactions` wraps Amazon Textract (no LLM).
- **Subagent tools** — `categorize_transactions` and `research_merchant` each wrap their own Claude call.

The lead orchestrator does no work itself; it sequences all three through the `stop_reason` loop.
