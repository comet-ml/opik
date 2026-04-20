#!/usr/bin/env bash
#
# Build and send the library integration tests Slack notification.
#
# Required env vars:
#   SLACK_WEBHOOK_URL    — Slack incoming webhook URL
#   SUITE_RESULTS        — JSON object mapping suite display names to results
#                          e.g. '{"OpenAI":"success","LangChain":"failure"}'
#   TRIGGER_TYPE         — "Daily Schedule", "Weekly Schedule", or "Manual Dispatch"
#   GITHUB_REF_NAME      — branch name (set by GitHub Actions)
#   GITHUB_SHA           — commit SHA (set by GitHub Actions)
#   GITHUB_REPOSITORY    — owner/repo (set by GitHub Actions)
#   GITHUB_RUN_ID        — workflow run ID (set by GitHub Actions)
#
# Optional env vars:
#   SLACK_USER_ID        — Slack user ID to cc on the notification
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

SHORT_SHA="${GITHUB_SHA:0:7}"
WORKFLOW_URL="https://github.com/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"
COMMIT_URL="https://github.com/${GITHUB_REPOSITORY}/commit/${GITHUB_SHA}"

# Categorize suites by result
SUCCESS_SUITES=""
FAILED_SUITES=""
SKIPPED_SUITES=""
SUCCESS_COUNT=0
FAILURE_COUNT=0
SKIPPED_COUNT=0

while IFS=$'\t' read -r name result; do
  case "$result" in
    success)
      [ -n "$SUCCESS_SUITES" ] && SUCCESS_SUITES="${SUCCESS_SUITES}, "
      SUCCESS_SUITES="${SUCCESS_SUITES}${name}"
      SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
      ;;
    failure)
      [ -n "$FAILED_SUITES" ] && FAILED_SUITES="${FAILED_SUITES}, "
      FAILED_SUITES="${FAILED_SUITES}${name}"
      FAILURE_COUNT=$((FAILURE_COUNT + 1))
      ;;
    *)
      [ -n "$SKIPPED_SUITES" ] && SKIPPED_SUITES="${SKIPPED_SUITES}, "
      SKIPPED_SUITES="${SKIPPED_SUITES}${name}"
      SKIPPED_COUNT=$((SKIPPED_COUNT + 1))
      ;;
  esac
done < <(echo "$SUITE_RESULTS" | jq -r 'to_entries[] | "\(.key)\t\(.value)"')

if [ "$FAILURE_COUNT" -gt 0 ]; then
  COLOR="danger"
  STATUS_TEXT="Failed"
else
  COLOR="good"
  STATUS_TEXT="Passed"
fi

# Build blocks with jq
BLOCKS='[]'

BLOCKS=$(echo "$BLOCKS" | jq --arg title "Python SDK Integration Tests $STATUS_TEXT" \
  '. + [{"type": "header", "text": {"type": "plain_text", "text": $title, "emoji": true}}]')

BLOCKS=$(echo "$BLOCKS" | jq \
  --arg trigger "$TRIGGER_TYPE" \
  --arg branch "$GITHUB_REF_NAME" \
  --arg commit_url "$COMMIT_URL" \
  --arg short_sha "$SHORT_SHA" \
  '. + [{"type": "section", "fields": [
    {"type": "mrkdwn", "text": ("*Trigger:*\n" + $trigger)},
    {"type": "mrkdwn", "text": ("*Branch:*\n`" + $branch + "`")},
    {"type": "mrkdwn", "text": ("*Commit:*\n<" + $commit_url + "|`" + $short_sha + "`>")}
  ]}]')

BLOCKS=$(echo "$BLOCKS" | jq \
  --arg s "$SUCCESS_COUNT" --arg f "$FAILURE_COUNT" --arg k "$SKIPPED_COUNT" \
  '. + [{"type": "section", "text": {"type": "mrkdwn",
    "text": ("\u2705 *Passed:* " + $s + "  |  \u274c *Failed:* " + $f + "  |  \u23ed\ufe0f *Skipped:* " + $k)}}]')

if [ -n "$FAILED_SUITES" ]; then
  BLOCKS=$(echo "$BLOCKS" | jq --arg suites "$FAILED_SUITES" \
    '. + [{"type": "section", "text": {"type": "mrkdwn", "text": ("\u274c *Failed:* " + $suites)}}]')
fi

if [ -n "$SUCCESS_SUITES" ]; then
  BLOCKS=$(echo "$BLOCKS" | jq --arg suites "$SUCCESS_SUITES" \
    '. + [{"type": "section", "text": {"type": "mrkdwn", "text": ("\u2705 *Passed:* " + $suites)}}]')
fi

if [ -n "$SKIPPED_SUITES" ]; then
  BLOCKS=$(echo "$BLOCKS" | jq --arg suites "$SKIPPED_SUITES" \
    '. + [{"type": "section", "text": {"type": "mrkdwn", "text": ("\u23ed\ufe0f *Skipped:* " + $suites)}}]')
fi

BLOCKS=$(echo "$BLOCKS" | jq --arg url "$WORKFLOW_URL" \
  '. + [{"type": "actions", "elements": [{"type": "button", "text": {"type": "plain_text", "text": "\ud83d\udd0d View Workflow", "emoji": true}, "url": $url}]}]')

if [ -n "${SLACK_USER_ID:-}" ]; then
  BLOCKS=$(echo "$BLOCKS" | jq --arg uid "$SLACK_USER_ID" \
    '. + [{"type": "context", "elements": [{"type": "mrkdwn", "text": ("\ud83d\udc64 cc: <@" + $uid + ">")}]}]')
fi

echo "$BLOCKS" | jq --arg color "$COLOR" '{"attachments": [{"color": $color, "blocks": .}]}' > /tmp/slack-payload.json

echo "Payload:"
cat /tmp/slack-payload.json

exec "$SCRIPT_DIR/send-slack-message.sh" /tmp/slack-payload.json
