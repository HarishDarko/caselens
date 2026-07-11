#!/usr/bin/env bash
set -euo pipefail

DLQ_URL="$(awslocal sqs create-queue --queue-name caselens-triage-dlq --query QueueUrl --output text)"
DLQ_ARN="$(awslocal sqs get-queue-attributes --queue-url "$DLQ_URL" --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)"
awslocal sqs create-queue \
  --queue-name caselens-triage \
  --attributes "RedrivePolicy={\"deadLetterTargetArn\":\"${DLQ_ARN}\",\"maxReceiveCount\":\"5\"}"
