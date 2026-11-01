# 03 - Document Intelligence (Textract + Bedrock + LLM)

A bank-statement PDF goes in; the transactions come back categorized as
**grocery / gas / restaurant / other**. The demo shows the architect-level split
between the two kinds of work:

| Stage | Job | Tool | Why |
|-------|-----|------|-----|
| 1 | Extract transactions from the PDF | **Amazon Textract** (async, TABLES) | Pulling exact dates/amounts is deterministic bookkeeping - a purpose-built OCR/table service, not an LLM |
| 2 | Categorize each transaction | **Claude Haiku on Amazon Bedrock** | Deciding `SQ *JOE'S` is a restaurant is fuzzy judgment - exactly what an LLM is for |

The categorizer reuses the **tool/contract pattern** from `02-subagent-brief`:
the model is given one tool whose input schema *is* the
[`CategorizationResult`](src/main/java/com/statement/model/CategorizationResult.java)
record, so the result is structured by construction and parsed straight back.

## Pipeline

```
POST /api/categorize (PDF)
   -> S3StorageService    : upload PDF to S3 (async Textract reads from S3)
   -> TextractService     : StartDocumentAnalysis(TABLES) -> poll -> blocks
   -> TextractTableParser : blocks -> List<Transaction>
   -> BedrockCategorizer  : Converse + submit_categorization tool -> CategorizationResult
   -> OrchestratorService : filter to grocery/gas/restaurant, delete the S3 object
-> JSON: { grocery[], gas[], restaurant[], all[], missingInfo[] }
```

## Prerequisites

- **AWS CLI authenticated** under a profile:
  `aws login --region us-east-2 --profile <your-profile>`
- **S3 bucket** — set `APP_S3_BUCKET` to your own bucket name (e.g. `cca-statement-demo-<ACCOUNT_ID>`) in `us-east-2`.
  Create it with: `export APP_S3_BUCKET=your-statement-bucket && aws s3 mb s3://$APP_S3_BUCKET --region us-east-2`
- **Bedrock model access** for Anthropic Claude in `us-east-2`. First use of a
  Claude model requires submitting the one-time **Anthropic use case details**
  form in the Bedrock console (Model access). Verify with:
  ```
  aws bedrock-runtime converse --region us-east-2 --profile ronny \
    --model-id us.anthropic.claude-haiku-4-5-20251001-v1:0 \
    --messages '[{"role":"user","content":[{"text":"Say OK"}]}]' \
    --inference-config '{"maxTokens":16}'
  ```

## Run

```
AWS_PROFILE=ronny ./mvnw spring-boot:run
# then, in another shell:
curl -F pdf=@sample-statement.pdf http://localhost:8080/api/categorize
```

## ⚠️ Security — demo only

These endpoints are **unauthenticated** and use `@CrossOrigin(origins = "*")`. They are a
learning demo, **not production code**. Do **not** expose them on a public host: anyone who can
reach them could upload documents and drive Textract / S3 / Claude calls (and your AWS + Anthropic
spend). Before any real deployment, add authentication (e.g. an API key or Cognito), lock CORS to a
known origin, and add rate limiting. Secrets are read from the environment (`ANTHROPIC_API_KEY`,
AWS credential chain) — never commit them.

## Configuration (`src/main/resources/application.properties`)

| Property | Default | Notes |
|----------|---------|-------|
| `aws.region` | `us-east-2` | Project Region; all resources live here |
| `app.s3.bucket` | `${APP_S3_BUCKET}` | Your bucket (set via env var); holds the PDF during extraction, then deleted |
| `app.bedrock.model-id` | `us.anthropic.claude-haiku-4-5-20251001-v1:0` | Cross-region inference profile |

## Cost / cleanup

Pennies: S3 is tiny, Textract free-tier covers a demo, Haiku is fractions of a
cent per statement, no EC2. The app deletes each uploaded PDF after processing.
To clear everything: `aws s3 rm s3://<bucket> --recursive`.

## Notes

- `TextractTableParser` is heuristic (bank layouts vary). If it struggles on a
  real statement, the documented fallback is to hand Textract's raw text to
  Bedrock and let it structure + categorize in one call.
- Tool choice is left at the default (not forced), matching `02-subagent-brief`:
  one tool + a strict instruction gets the call, and the code throws if the model
  answers with prose instead.
