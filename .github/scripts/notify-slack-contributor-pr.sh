#!/usr/bin/env bash
#
# Build and send the external-contributor PR Slack notification.
#
# Mirrors the message format produced by the `send-code-review-slack` skill
# (.agents/commands/comet/send-code-review-slack.md).
#
# Unlike .github/scripts/send-slack-message.sh, this script treats a missing
# SLACK_WEBHOOK_URL as a hard error. The caller workflow gates the marker
# comment on this script writing `sent=true` to $GITHUB_OUTPUT, so silently
# skipping on a missing webhook would let the marker get posted anyway and
# permanently block re-notification for the PR.
#
# Required env vars:
#   SLACK_WEBHOOK_URL — Slack incoming webhook URL (must be non-empty)
#   PR_URL            — URL of the PR
#   PR_NUMBER         — PR number
#   PR_TITLE          — PR title (used to extract the Jira key only)
#   AUTHOR_LOGIN      — PR author's GitHub login
#
# Optional env vars:
#   AUTHOR_DISPLAY    — PR author's display name (falls back to AUTHOR_LOGIN)
#   PR_LABELS         — comma-separated label names from .github/labeler.yml,
#                       used to emit component lines (Frontend / Backend /
#                       Python SDK / TypeScript SDK). Empty / missing labels
#                       just means no component lines.
#   GITHUB_OUTPUT     — when set (in CI), `sent=true` is appended on HTTP 200
#
set -euo pipefail

: "${SLACK_WEBHOOK_URL:?SLACK_WEBHOOK_URL is required (refusing to silently skip)}"
: "${PR_URL:?PR_URL is required}"
: "${PR_NUMBER:?PR_NUMBER is required}"
: "${PR_TITLE:?PR_TITLE is required}"
: "${AUTHOR_LOGIN:?AUTHOR_LOGIN is required}"

JIRA_LINE=""
if [[ "$PR_TITLE" =~ ^\[(OPIK-[0-9]+)\] ]]; then
  JIRA_KEY="${BASH_REMATCH[1]}"
  JIRA_LINE=":jira_epic: jira link: https://comet-ml.atlassian.net/browse/${JIRA_KEY}"
fi

COMPONENT_LINES=()
# Map .github/labeler.yml scope labels to component lines. The first
# four mirror the send-code-review-slack skill's emoji set. `:gear:`
# for Infrastructure is added here so contributor PRs that are purely
# CI / Docker / scripts (no recognized FE/BE/SDK label) still surface
# a "this is an infra change" cue to reviewers rather than landing
# with zero component lines.
# Order is deterministic regardless of how labels appear in PR_LABELS,
# so cross-cutting PRs always render the same.
LABELS_CSV=",${PR_LABELS:-},"
if [[ "$LABELS_CSV" == *",Frontend,"* ]]; then
  COMPONENT_LINES+=(":react: fe change")
fi
if [[ "$LABELS_CSV" == *",Backend,"* ]]; then
  COMPONENT_LINES+=(":java: be change")
fi
if [[ "$LABELS_CSV" == *",Python SDK,"* ]]; then
  COMPONENT_LINES+=(":python: python change")
fi
if [[ "$LABELS_CSV" == *",TypeScript SDK,"* ]]; then
  COMPONENT_LINES+=(":typescript: typescript change")
fi
if [[ "$LABELS_CSV" == *",Infrastructure,"* ]]; then
  COMPONENT_LINES+=(":gear: infra change")
fi

AUTHOR_PROFILE_URL="https://github.com/${AUTHOR_LOGIN}"
if [ -n "${AUTHOR_DISPLAY:-}" ]; then
  AUTHOR_LINE=":bust_in_silhouette: author: ${AUTHOR_DISPLAY} (<${AUTHOR_PROFILE_URL}|@${AUTHOR_LOGIN}>)"
else
  AUTHOR_LINE=":bust_in_silhouette: author: <${AUTHOR_PROFILE_URL}|@${AUTHOR_LOGIN}>"
fi
PR_LINE=":github: pr link: <${PR_URL}|#${PR_NUMBER}>"
TITLE_LINE=":memo: title: ${PR_TITLE}"

MESSAGE_TEXT="Hi team,

Please review the following PR from an external contributor:

${TITLE_LINE}"

if [ -n "$JIRA_LINE" ]; then
  MESSAGE_TEXT+="
${JIRA_LINE}"
fi

MESSAGE_TEXT+="
${PR_LINE}
${AUTHOR_LINE}"

for line in ${COMPONENT_LINES[@]+"${COMPONENT_LINES[@]}"}; do
  MESSAGE_TEXT+="
${line}"
done

jq -n --arg text "$MESSAGE_TEXT" '{text: $text}' > /tmp/slack-payload.json

HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' \
  -X POST -H 'Content-type: application/json' \
  --data @/tmp/slack-payload.json "$SLACK_WEBHOOK_URL")

if [ "$HTTP_CODE" != "200" ]; then
  echo "::error::Slack webhook returned HTTP $HTTP_CODE"
  exit 1
fi

echo "Slack notification sent (HTTP 200)"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "sent=true" >> "$GITHUB_OUTPUT"
fi
