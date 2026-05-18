#!/usr/bin/env bash
#
# Build and send the external-contributor PR Slack notification.
#
# Mirrors the message format produced by the `send-code-review-slack` skill
# (.agents/commands/comet/send-code-review-slack.md).
#
# Required env vars:
#   SLACK_WEBHOOK_URL — Slack incoming webhook URL
#   PR_URL            — URL of the PR
#   PR_NUMBER         — PR number
#   PR_TITLE          — PR title (used to extract Jira key and component tag)
#   AUTHOR_LOGIN      — PR author's GitHub login
#
# Optional env vars:
#   AUTHOR_DISPLAY    — PR author's display name (falls back to AUTHOR_LOGIN)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

: "${PR_URL:?PR_URL is required}"
: "${PR_NUMBER:?PR_NUMBER is required}"
: "${PR_TITLE:?PR_TITLE is required}"
: "${AUTHOR_LOGIN:?AUTHOR_LOGIN is required}"

JIRA_LINE=""
if [[ "$PR_TITLE" =~ ^\[(OPIK-[0-9]+)\] ]]; then
  JIRA_KEY="${BASH_REMATCH[1]}"
  JIRA_LINE=":jira_epic: jira link: https://comet-ml.atlassian.net/browse/${JIRA_KEY}"
fi

COMPONENT_LINE=""
COMPONENT_TAG=""
if [[ "$PR_TITLE" =~ ^\[[^]]+\]\ *\[([A-Za-z]+)\] ]]; then
  COMPONENT_TAG="$(printf '%s' "${BASH_REMATCH[1]}" | tr '[:lower:]' '[:upper:]')"
elif [[ "$PR_TITLE" =~ ^\[([A-Za-z]+)\] ]]; then
  COMPONENT_TAG="$(printf '%s' "${BASH_REMATCH[1]}" | tr '[:lower:]' '[:upper:]')"
fi
case "$COMPONENT_TAG" in
  FE)
    COMPONENT_LINE=":react: fe change"
    ;;
  BE)
    COMPONENT_LINE=":java: be change"
    ;;
esac

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

if [ -n "$COMPONENT_LINE" ]; then
  MESSAGE_TEXT+="
${COMPONENT_LINE}"
fi

jq -n --arg text "$MESSAGE_TEXT" '{text: $text}' > /tmp/slack-payload.json

exec "$SCRIPT_DIR/send-slack-message.sh" /tmp/slack-payload.json
