# Agentic Document Intelligence

A bank-statement PDF goes in; the transactions come back **categorized** as
grocery / gas / restaurant / other. Built on **Amazon Textract** (extraction) +
**Claude** (categorization), it shows two things an architect cares about:

1. **The deterministic vs. judgment split.** Textract does the deterministic
   OCR/table extraction; Claude does the fuzzy categorization. Use the right tool
   for each job.
2. **Workflow vs. agent.** The same problem is solved two ways — a fixed
   pipeline, and an **agentic multi-agent orchestrator** that drives a visible
   `stop_reason` loop and *decides for itself* when to escalate an unclear
   merchant to a research subagent.

Claude is reachable two ways behind one interface: **Amazon Bedrock** (IAM) or
the **Anthropic API** (key) — flip with one config flag.

| Stage | Job | Tool | Why |
|-------|-----|------|-----|
| Extract | Pull dates/amounts from the PDF | **Amazon Textract** (async, TABLES) | Deterministic bookkeeping — a purpose-built OCR/table service, not an LLM |
| Categorize | Label each transaction | **Claude Haiku** (Bedrock or Anthropic API) | Deciding `SQ *JOE'S` is a restaurant is fuzzy judgment — what an LLM is for |
| Research | Identify a cryptic merchant | **Claude subagent** | The lead agent escalates only low-confidence rows |

## Endpoints

| Method / path | What it does | Needs |
|---|---|---|
| `POST /api/categorize` | Deterministic pipeline: PDF → Textract → Claude → JSON | AWS + Claude |
| `POST /api/agentic/process-pdf` | **Agentic** pipeline: a lead agent extracts (Textract), categorizes + researches (Claude subagents), summarizes — returns the categorized rows **and** the full loop trace | AWS + Claude |
| `POST /api/agentic/process` | Agentic over a JSON array of transactions (no AWS — just a Claude key) | Claude only |

## Pipeline (deterministic path)

```
POST /api/categorize (PDF)
   -> S3StorageService    : upload PDF to S3 (async Textract reads from S3)
   -> TextractService     : StartDocumentAnalysis(TABLES) -> poll -> blocks
   -> TextractTableParser : blocks -> List<Transaction>
   -> Categorizer         : one tool whose input schema IS the result -> CategorizationResult
   -> OrchestratorService : group into grocery/gas/restaurant, delete the S3 object
-> JSON: { grocery[], gas[], restaurant[], all[], missingInfo[] }
```

The **agentic path** (`/api/agentic/process-pdf`) wraps the same services as
*tools* a lead agent calls through a `stop_reason` loop — `extract_transactions`
(Textract), `categorize_transactions` and `research_merchant` (Claude subagents).
See [`docs/flow.md`](docs/flow.md) for the sequence and loop diagrams.

## Getting started

**Prerequisites:** Java 21, an AWS account (S3 + Textract in `us-east-2`), and an
**Anthropic API key** (and/or Bedrock model access — see below).

```bash
# 1. Clone and enter
git clone https://github.com/roopannava/agentic-document-intelligence.git
cd agentic-document-intelligence

# 2. Create three local config files (kept in /tmp — never committed)

#    a) your Anthropic API key
echo 'ANTHROPIC_API_KEY=sk-ant-your-key' > /tmp/anthropic.env

#    b) temporary AWS credentials for S3 + Textract (use YOUR profile)
aws login --profile <your-profile>
aws configure export-credentials --profile <your-profile> --format env > /tmp/aws_creds.env

#    c) your S3 bucket name, and create the bucket once
echo 'export APP_S3_BUCKET=your-statement-bucket' > /tmp/app.env
aws s3 mb s3://your-statement-bucket --region us-east-2

# 3. Start the server (run.sh loads all three + the key)
./run.sh

# 4. In another terminal — test with the bundled synthetic statement
./test.sh                       # uses samples/sample_statement.pdf
./test.sh /path/to/your.pdf     # or your own PDF
```

> **Note:** AWS temporary credentials expire (a few hours). When S3/Textract
> start returning *"token expired"*, re-run steps 2b, then restart `./run.sh`.
> The Anthropic key and bucket files persist.

A bundled **synthetic** statement lives at
[`samples/sample_statement.pdf`](samples/sample_statement.pdf) (fictional data —
no real PII) so `./test.sh` works immediately after cloning.

## Configuration (`src/main/resources/application.properties`)

| Property | Default | Notes |
|----------|---------|-------|
| `aws.region` | `us-east-2` | All AWS resources live here |
| `app.s3.bucket` | `${APP_S3_BUCKET}` | Your bucket (env var); holds the PDF during extraction, then deleted |
| `app.llm.provider` | `anthropic` | `bedrock` (Claude via AWS Bedrock, IAM) or `anthropic` (Claude via the Anthropic API, key) |
| `app.bedrock.model-id` | `us.anthropic.claude-haiku-4-5-...` | Used when provider=bedrock |
| `app.anthropic.model-id` | `claude-haiku-5-5` | Used when provider=anthropic |
| `app.agentic.lead-model` / `subagent-model` | `claude-haiku-5-5` | The agentic orchestrator + subagents |

**Choosing the door:** set `app.llm.provider=bedrock` to reach Claude through
Amazon Bedrock (no key — IAM governs it, data stays in your AWS account), or
`anthropic` to call the Anthropic API directly with `ANTHROPIC_API_KEY`. Same
model, same code — one flag. (Bedrock also needs one-time Claude model access in
the Bedrock console.)

## ⚠️ Security — demo only

These endpoints are **unauthenticated** and use `@CrossOrigin(origins = "*")`.
They are a learning demo, **not production code**. Do **not** expose them on a
public host: anyone who can reach them could upload documents and drive
Textract / S3 / Claude calls (and your AWS + Anthropic spend). Before any real
deployment, add authentication (API key / Cognito), lock CORS to a known origin,
and add rate limiting. Secrets are read from the environment
(`ANTHROPIC_API_KEY`, the AWS credential chain) — never commit them.

## Data handling

The uploaded PDF is deleted from S3 in a `finally` block right after Textract
reads it, so the raw statement (PII) is kept no longer than extraction needs it.
S3 keys are UUID-prefixed and the filename is sanitized, so the bucket is empty
between requests by design.

## Cost / cleanup

Pennies: S3 is tiny, Textract free-tier covers a demo, Haiku is fractions of a
cent per statement, no EC2. To clear the bucket: `aws s3 rm s3://$APP_S3_BUCKET --recursive`.

## Notes

- `TextractTableParser` is heuristic (bank layouts vary). If it struggles on a
  real statement, the documented fallback is to hand Textract's raw text to the
  LLM and let it structure + categorize in one call.
- Tool choice is left at the default (not forced): one tool + a strict
  instruction gets the call. (Forced tool choice is rejected on current Claude
  models anyway — `auto` is the right pattern.)
- Stack: Java 21, Spring Boot, AWS SDK v2 (S3 / Textract / Bedrock), Anthropic Java SDK.
