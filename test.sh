#!/usr/bin/env bash
#
# Test the agentic document-intelligence pipeline (/api/agentic/process-pdf).
# The lead orchestrator uploads the PDF, then extracts (Textract) -> categorizes
# -> researches unclear merchants -> summarizes, via the stop_reason loop.
#
# Usage:
#   ./test.sh                         # uses ~/Downloads/test.pdf
#   ./test.sh /path/to/statement.pdf  # use a specific PDF
#
# Requires: the server running on :8080, started with AWS creds + ANTHROPIC_API_KEY:
#   set -a; source /tmp/aws_creds.env; source /tmp/anthropic.env; set +a
#   ./mvnw spring-boot:run

set -euo pipefail

PDF="${1:-$HOME/Downloads/test.pdf}"
URL="http://localhost:8080/api/agentic/process-pdf"
OUT="/tmp/agentic_pdf_resp.json"

if [[ ! -f "$PDF" ]]; then
  echo "❌ PDF not found: $PDF"
  echo "   Usage: ./test.sh /path/to/statement.pdf"
  exit 1
fi

echo "POST $URL"
echo "PDF:  $PDF"
echo "(extraction is async — this can take 15-60s)"
echo

HTTP=$(curl -s --max-time 240 \
  -o "$OUT" \
  -w "%{http_code}" \
  -F "pdf=@${PDF}" \
  "$URL") || {
    echo "❌ curl failed — is the server running on :8080?"
    echo "   Start it:  set -a; source /tmp/aws_creds.env; source /tmp/anthropic.env; set +a; ./mvnw spring-boot:run"
    exit 1
  }

echo "HTTP $HTTP"
echo
if command -v jq >/dev/null 2>&1; then
  echo "================ CATEGORIZED ROWS ================"
  printf "CATEGORY\tCONF\tAMOUNT\tDESCRIPTION\n"
  jq -r '.categorized[]? | "\(.category)\t\(.confidence)\t\(.amount)\t\(.description)"' "$OUT" \
    | column -t -s $'\t'
  echo
  echo "================ SUMMARY ================"
  jq -r '.finalAnswer' "$OUT"
  echo
  echo "================ TRACE ================"
  jq -r '.trace[]' "$OUT"
else
  echo "(install jq for the formatted view — raw JSON below)"
  python3 -m json.tool "$OUT" 2>/dev/null || cat "$OUT"
fi
