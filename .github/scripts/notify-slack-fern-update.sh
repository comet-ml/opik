#!/usr/bin/env bash
#
# Post the FERN update PR notification to #code-review as a single daily thread.
#
# The first FERN PR of a (UTC) day posts a parent message; every later PR that
# day replies in that thread, so the channel sees one top-level message per day.
# The thread anchor (date + Slack ts) is carried between workflow runs in the
# `slack-thread-state` artifact — there is no GitHub state to mutate.
#
# Required env vars:
#   SLACK_BOT_TOKEN_CODE_REVIEW    — bot token with chat:write + chat:write.customize
#   SLACK_CODE_REVIEW_CHANNEL_ID   — channel ID of #code-review
#   GH_TOKEN                       — token with actions:read (download prior artifact)
#   GITHUB_REPOSITORY              — owner/repo (set by GitHub Actions)
#   GITHUB_SHA                     — commit SHA (set by GitHub Actions)
#   STATE_ARTIFACT_NAME            — artifact name holding the thread state
#   STATE_FILE                     — JSON filename inside the artifact / to write back
#   PR_URL                         — URL of the FERN PR
#   PR_NUMBER                      — FERN PR number
#   AUTHOR_DISPLAY                 — GitHub username of the triggering author
#
# Optional env vars:
#   ORIGINATING_PR                 — URL of the BE PR that triggered FERN generation
#   MENTION                        — pre-resolved Slack mention (e.g. "<@U123>"), may be empty
#
set -euo pipefail

BOT_NAME="Fern Bot"
BOT_ICON=":herb:"
TODAY="$(date -u +%Y-%m-%d)"

MISSING=""
[ -z "${SLACK_BOT_TOKEN:-}" ] && MISSING="SLACK_BOT_TOKEN_CODE_REVIEW"
[ -z "${SLACK_CODE_REVIEW_CHANNEL_ID:-}" ] && MISSING="${MISSING:+$MISSING, }SLACK_CODE_REVIEW_CHANNEL_ID"
if [ -n "$MISSING" ]; then
  echo "::warning title=Slack notification skipped::Missing repo secret(s): ${MISSING}. The FERN PR was still created. To enable the #code-review thread, set these (bot needs chat:write + chat:write.customize, invited to #code-review)."
  exit 0
fi

# Resolve the day's thread ts from the most recent state artifact. Each run uploads its
# own artifact, so we ask for the latest one by name (the API returns artifacts newest
# first) rather than the latest run, which may have uploaded nothing. None / expired ->
# first post of the day / fresh thread.
THREAD_TS=""
ARTIFACT_ID="$(gh api "repos/$GITHUB_REPOSITORY/actions/artifacts?name=$STATE_ARTIFACT_NAME&per_page=1" \
  --jq '.artifacts[0] | select(.expired == false) | .id' 2>/dev/null || echo "")"
if [ -n "$ARTIFACT_ID" ] && [ "$ARTIFACT_ID" != "null" ]; then
  if gh api "repos/$GITHUB_REPOSITORY/actions/artifacts/$ARTIFACT_ID/zip" > /tmp/fern-state.zip 2>/dev/null \
      && unzip -o -q /tmp/fern-state.zip -d /tmp/fern-state 2>/dev/null; then
    PREV_DATE="$(jq -r '.date // ""' "/tmp/fern-state/$STATE_FILE" 2>/dev/null || echo "")"
    if [ "$PREV_DATE" = "$TODAY" ]; then
      THREAD_TS="$(jq -r '.ts // ""' "/tmp/fern-state/$STATE_FILE" 2>/dev/null || echo "")"
    fi
  fi
fi

# Build the message body. TRIGGER_BODY holds the part after the "Triggered by:"
# label; jq joins them with a real newline (bash double quotes don't expand \n).
SHORT_SHA="${GITHUB_SHA:0:7}"
if [ -n "${ORIGINATING_PR:-}" ]; then
  TRIGGER_BODY="<${ORIGINATING_PR}|BE PR> by ${AUTHOR_DISPLAY}"
else
  TRIGGER_BODY="Commit <https://github.com/${GITHUB_REPOSITORY}/commit/${GITHUB_SHA}|\`${SHORT_SHA}\`> by ${AUTHOR_DISPLAY}"
fi

MENTION_TEXT=""
if [ -n "${MENTION:-}" ]; then
  MENTION_TEXT="${MENTION} Your BE merge triggered FERN changes. Please review."
fi

PAYLOAD="$(jq -n \
  --arg channel "$SLACK_CODE_REVIEW_CHANNEL_ID" \
  --arg username "$BOT_NAME" \
  --arg icon "$BOT_ICON" \
  --arg thread_ts "$THREAD_TS" \
  --arg pr_url "$PR_URL" \
  --arg pr_num "$PR_NUMBER" \
  --arg trigger_body "$TRIGGER_BODY" \
  --arg mention "$MENTION_TEXT" \
  '
  {
    channel: $channel,
    username: $username,
    icon_emoji: $icon,
    text: ("FERN Update PR #" + $pr_num + " — review needed"),
    attachments: [{
      color: "#36a64f",
      blocks: (
        [
          {"type": "section", "text": {"type": "mrkdwn", "text": "A BE merge to main changed the OpenAPI spec. FERN SDK code has been regenerated."}},
          {"type": "section", "fields": [
            {"type": "mrkdwn", "text": ("*FERN PR:*\n<" + $pr_url + "|#" + $pr_num + ">")},
            {"type": "mrkdwn", "text": ("*Triggered by:*\n" + $trigger_body)}
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
  + (if $thread_ts != "" then {thread_ts: $thread_ts} else {} end)
  ')"

RESPONSE="$(curl -sS -X POST https://slack.com/api/chat.postMessage \
  -H "Authorization: Bearer ${SLACK_BOT_TOKEN}" \
  -H 'Content-type: application/json; charset=utf-8' \
  --data "$PAYLOAD")"

if [ "$(jq -r '.ok' <<<"$RESPONSE")" != "true" ]; then
  echo "::error::Slack chat.postMessage failed: $(jq -r '.error // "unknown"' <<<"$RESPONSE")"
  exit 1
fi
echo "Slack message posted ($([ -n "$THREAD_TS" ] && echo "threaded reply" || echo "new daily parent"))"

# Persist the day's anchor for the next run. The parent ts is the thread root;
# on a reply we keep the existing root, so the thread stays one-per-day.
ROOT_TS="${THREAD_TS:-$(jq -r '.ts' <<<"$RESPONSE")}"
jq -n --arg date "$TODAY" --arg ts "$ROOT_TS" '{date: $date, ts: $ts}' > "$STATE_FILE"
