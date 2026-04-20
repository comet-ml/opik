#!/usr/bin/env bash
#
# Build and send the FERN update PR Slack notification.
#
# Required env vars:
#   SLACK_WEBHOOK_URL    — Slack incoming webhook URL
#   PR_URL               — URL of the FERN PR
#   PR_NUMBER            — PR number
#   AUTHOR_DISPLAY       — GitHub username of the author
#   GITHUB_SHA           — commit SHA (set by GitHub Actions)
#   GITHUB_REPOSITORY    — owner/repo (set by GitHub Actions)
#
# Optional env vars:
#   ORIGINATING_PR       — URL of the BE PR that triggered FERN generation
#   MENTION              — pre-resolved Slack mention (e.g. "<@U123>"), may be empty
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

SHORT_SHA="${GITHUB_SHA:0:7}"

if [ -n "${ORIGINATING_PR:-}" ]; then
  TRIGGER_TEXT="*Triggered by:*\n<${ORIGINATING_PR}|BE PR> by ${AUTHOR_DISPLAY}"
else
  TRIGGER_TEXT="*Triggered by:*\nCommit <https://github.com/${GITHUB_REPOSITORY}/commit/${GITHUB_SHA}|\`${SHORT_SHA}\`> by ${AUTHOR_DISPLAY}"
fi

MENTION_TEXT=""
if [ -n "${MENTION:-}" ]; then
  MENTION_TEXT="${MENTION} Your BE merge triggered FERN changes. Please review."
fi

jq -n \
  --arg pr_url "$PR_URL" \
  --arg pr_num "$PR_NUMBER" \
  --arg trigger "$TRIGGER_TEXT" \
  --arg mention "$MENTION_TEXT" \
  '
  {
    "attachments": [{
      "color": "#36a64f",
      "blocks": (
        [
          {"type": "header", "text": {"type": "plain_text", "text": "FERN Update PR \u2014 Review Needed", "emoji": true}},
          {"type": "section", "text": {"type": "mrkdwn", "text": "A BE merge to main changed the OpenAPI spec. FERN SDK code has been regenerated."}},
          {"type": "section", "fields": [
            {"type": "mrkdwn", "text": ("*FERN PR:*\n<" + $pr_url + "|#" + $pr_num + ">")},
            {"type": "mrkdwn", "text": $trigger}
          ]}
        ]
        + (if $mention != "" then [{"type": "section", "text": {"type": "mrkdwn", "text": $mention}}] else [] end)
        + [
          {"type": "actions", "elements": [{
            "type": "button",
            "text": {"type": "plain_text", "text": "Review FERN PR", "emoji": true},
            "url": $pr_url,
            "style": "primary"
          }]}
        ]
      )
    }]
  }
  ' > /tmp/slack-payload.json

exec "$SCRIPT_DIR/send-slack-message.sh" /tmp/slack-payload.json
