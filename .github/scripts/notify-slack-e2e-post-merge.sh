#!/usr/bin/env bash
#
# Build and send the E2E post-merge Slack notification.
#
# Required env vars:
#   SLACK_WEBHOOK_URL    — Slack incoming webhook URL
#   TEST_RESULT          — "success" or "failure"
#   SUITE_NAME           — test suite name (e.g. "happypaths")
#   PASSED_TESTS         — count of passed tests
#   FAILED_TESTS         — count of failed tests
#   TESTOPS_URL          — link to TestOps dashboard
#   AUTHOR_DISPLAY       — GitHub username of the PR author
#   MENTIONS             — pre-resolved Slack mentions (e.g. "<@U123> <@U456>"), may be empty
#   GITHUB_REF_NAME      — branch name (set by GitHub Actions)
#   GITHUB_SHA           — commit SHA (set by GitHub Actions)
#   GITHUB_REPOSITORY    — owner/repo (set by GitHub Actions)
#   GITHUB_RUN_ID        — workflow run ID (set by GitHub Actions)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

SHORT_SHA="${GITHUB_SHA:0:7}"
WORKFLOW_URL="https://github.com/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"

if [ "$TEST_RESULT" == "success" ]; then
  STATUS_EMOJI="\u2705"
  STATUS_TEXT="Passed"
  COLOR="good"
else
  STATUS_EMOJI="\u274c"
  STATUS_TEXT="Failed"
  COLOR="danger"
fi

MENTION_BLOCK=""
if [ "$TEST_RESULT" == "failure" ] && [ -n "${MENTIONS:-}" ]; then
  MENTION_BLOCK=',
        {
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": "\ud83d\udea8 '"${MENTIONS}"' - Tests need attention!"
          }
        }'
fi

cat << EOF > /tmp/slack-payload.json
{
  "attachments": [
    {
      "color": "$COLOR",
      "blocks": [
        {
          "type": "header",
          "text": {
            "type": "plain_text",
            "text": "$STATUS_EMOJI E2E Tests $STATUS_TEXT - Post Merge",
            "emoji": true
          }
        }$MENTION_BLOCK,
        {
          "type": "section",
          "fields": [
            {"type": "mrkdwn", "text": "*Suite:*\n\`${SUITE_NAME}\`"},
            {"type": "mrkdwn", "text": "*Branch:*\n\`${GITHUB_REF_NAME}\`"},
            {"type": "mrkdwn", "text": "*Results:*\n\u2705 ${PASSED_TESTS} passed, \u274c ${FAILED_TESTS} failed"},
            {"type": "mrkdwn", "text": "*Commit:*\n<https://github.com/${GITHUB_REPOSITORY}/commit/${GITHUB_SHA}|\`${SHORT_SHA}\`>"}
          ]
        },
        {
          "type": "context",
          "elements": [
            {"type": "mrkdwn", "text": "\ud83d\udc64 *Author:* ${AUTHOR_DISPLAY}"}
          ]
        },
        {
          "type": "actions",
          "elements": [
            {
              "type": "button",
              "text": {"type": "plain_text", "text": "\ud83d\udcca TestOps", "emoji": true},
              "url": "${TESTOPS_URL}",
              "style": "primary"
            },
            {
              "type": "button",
              "text": {"type": "plain_text", "text": "\ud83d\udd0d Workflow", "emoji": true},
              "url": "${WORKFLOW_URL}"
            }
          ]
        }
      ]
    }
  ]
}
EOF

exec "$SCRIPT_DIR/send-slack-message.sh" /tmp/slack-payload.json
