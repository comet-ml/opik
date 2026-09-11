#!/usr/bin/env bash
#
# Send a JSON payload to a Slack webhook.
#
# Required env vars:
#   SLACK_WEBHOOK_URL — Slack incoming webhook URL
#
# Usage:
#   send-slack-message.sh payload.json
#   build-payload | send-slack-message.sh /dev/stdin
#
set -euo pipefail

PAYLOAD_FILE="${1:?Usage: send-slack-message.sh <payload-file>}"

if [ -z "${SLACK_WEBHOOK_URL:-}" ]; then
  echo "::notice::SLACK_WEBHOOK_URL not configured - Slack notification will be skipped"
  exit 0
fi

if [ ! -f "$PAYLOAD_FILE" ] && [ "$PAYLOAD_FILE" != "/dev/stdin" ]; then
  echo "::error::Payload file not found: $PAYLOAD_FILE"
  exit 1
fi

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST -H 'Content-type: application/json' \
  --data @"$PAYLOAD_FILE" "$SLACK_WEBHOOK_URL")

if [ "$HTTP_CODE" -eq 200 ]; then
  echo "Slack notification sent"
else
  echo "::warning::Slack notification failed with HTTP code: $HTTP_CODE"
  exit 1
fi
