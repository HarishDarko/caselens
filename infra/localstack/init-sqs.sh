#!/usr/bin/env bash
set -euo pipefail

DLQ_URL="$(awslocal sqs create-queue --queue-name caselens-triage-dlq --query QueueUrl --output text)"
DLQ_ARN="$(awslocal sqs get-queue-attributes --queue-url "$DLQ_URL" --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)"
ATTRIBUTES_FILE="$(mktemp)"
trap 'rm -f "$ATTRIBUTES_FILE"' EXIT

cat > "$ATTRIBUTES_FILE" <<EOF
{"RedrivePolicy":"{\"deadLetterTargetArn\":\"${DLQ_ARN}\",\"maxReceiveCount\":\"5\"}"}
EOF

awslocal sqs create-queue \
  --queue-name caselens-triage \
  --attributes "file://${ATTRIBUTES_FILE}"
