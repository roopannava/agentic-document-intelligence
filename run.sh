#!/usr/bin/env bash
#
# Start the Spring Boot app with AWS creds + Anthropic key loaded into its
# environment. Run from any terminal: ./run.sh
#
# Prereqs (create these once per AWS session / once for the key):
#   aws configure export-credentials --profile ronny --format env > /tmp/aws_creds.env
#   echo 'ANTHROPIC_API_KEY=sk-ant-...' > /tmp/anthropic.env

set -euo pipefail
cd "$(dirname "$0")"

AWS_ENV="/tmp/aws_creds.env"
ANTHROPIC_ENV="/tmp/anthropic.env"

if [[ ! -f "$AWS_ENV" ]]; then
  echo "❌ $AWS_ENV not found. AWS creds (for S3 + Textract) are needed. Create it:"
  echo "   aws configure export-credentials --profile ronny --format env > $AWS_ENV"
  exit 1
fi
if [[ ! -f "$ANTHROPIC_ENV" ]]; then
  echo "❌ $ANTHROPIC_ENV not found. Create it with your Anthropic key:"
  echo "   echo 'ANTHROPIC_API_KEY=sk-ant-...' > $ANTHROPIC_ENV"
  exit 1
fi

# Load into the environment the app will inherit.
set -a
source "$AWS_ENV"
source "$ANTHROPIC_ENV"
# Optional app config (e.g. APP_S3_BUCKET=your-bucket) — kept out of source control.
[[ -f /tmp/app.env ]] && source /tmp/app.env
set +a

if [[ -z "${APP_S3_BUCKET:-}" ]]; then
  echo "⚠️  APP_S3_BUCKET not set — the app will use the placeholder default and S3/Textract will fail."
  echo "   Set it:  echo 'export APP_S3_BUCKET=your-statement-bucket' > /tmp/app.env"
fi

echo "✅ AWS creds + ANTHROPIC_API_KEY loaded. Starting Spring Boot on :8080 ..."
echo "   (Ctrl-C to stop. Run ./test.sh in another terminal to hit it.)"
exec ./mvnw spring-boot:run
