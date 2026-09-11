#!/usr/bin/env bash
#
# Build and send a simple test-failure Slack notification.
#
# Required env vars:
#   SLACK_WEBHOOK_URL  — Slack incoming webhook URL
#   GITHUB_REF_NAME    — branch name (set by GitHub Actions)
#   GITHUB_SHA         — commit SHA (set by GitHub Actions)
#   GITHUB_REPOSITORY  — owner/repo (set by GitHub Actions)
#   GITHUB_RUN_ID      — workflow run ID (set by GitHub Actions)
#   GITHUB_EVENT_NAME  — trigger event (set by GitHub Actions)
#   GITHUB_ACTOR       — user who triggered the workflow (set by GitHub Actions)
#
# Usage:
#   notify-slack-test-failure.sh "Guardrails E2E Tests"
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEST_NAME="${1:?Usage: notify-slack-failure.sh <test-name>}"

SHORT_SHA="${GITHUB_SHA:0:7}"
WORKFLOW_URL="https://github.com/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"

cat << EOF > /tmp/slack-payload.json
{
  "attachments": [
    {
      "color": "danger",
      "blocks": [
        {
          "type": "header",
          "text": {
            "type": "plain_text",
            "text": "\u274c ${TEST_NAME} Failed",
            "emoji": true
          }
        },
        {
          "type": "section",
          "fields": [
            {"type": "mrkdwn", "text": "*Branch:*\n\`${GITHUB_REF_NAME}\`"},
            {"type": "mrkdwn", "text": "*Trigger:*\n\`${GITHUB_EVENT_NAME}\`"},
            {"type": "mrkdwn", "text": "*Author:*\n${GITHUB_ACTOR}"},
            {"type": "mrkdwn", "text": "*Commit:*\n<https://github.com/${GITHUB_REPOSITORY}/commit/${GITHUB_SHA}|\`${SHORT_SHA}\`>"}
          ]
        },
        {
          "type": "actions",
          "elements": [
            {
              "type": "button",
              "text": {"type": "plain_text", "text": "\ud83d\udd0d View Workflow", "emoji": true},
              "url": "${WORKFLOW_URL}",
              "style": "primary"
            }
          ]
        }
      ]
    }
  ]
}
EOF

exec "$SCRIPT_DIR/send-slack-message.sh" /tmp/slack-payload.json
