#!/usr/bin/env bash
# Needs GH_TOKEN, REPO and (unless DRY_RUN=true) SLACK_WEBHOOK_URL; BOT_BRANCHES overrides the branches checked.
set -euo pipefail

BOT_BRANCHES="${BOT_BRANCHES:-github-actions/NA-sync-provider-models github-actions/NA-automatically-update-model-prices-file}"
# Anything but green or still running needs a person once it has sat for a day, so a cancelled run counts too.
FAILED_STATES='["FAILURE","TIMED_OUT","ERROR","STARTUP_FAILURE","CANCELLED","ACTION_REQUIRED","STALE"]'

alerts=()
for branch in $BOT_BRANCHES; do
  # A fork PR can use the same branch name; only the bot's own PR, in this repository, counts.
  pr=$(gh pr list --repo "$REPO" --head "$branch" --state open \
    --json number,title,url,createdAt,isCrossRepository,statusCheckRollup \
    --jq '[.[] | select(.isCrossRepository | not)][0] // empty')
  if [ -z "$pr" ]; then
    echo "No open PR on $branch."
    continue
  fi
  # Slack reads <...> in a message as a link or a mention, so the title is escaped.
  title=$(jq -r '.title | gsub("&"; "&amp;") | gsub("<"; "&lt;") | gsub(">"; "&gt;")' <<<"$pr")
  url=$(jq -r '.url' <<<"$pr")
  age_hours=$(jq -r '((now - (.createdAt | fromdateiso8601)) / 3600) | floor' <<<"$pr")
  # A check run reports its result in conclusion, a commit status in state; a running check has neither yet.
  failing=$(jq -r --argjson failed "$FAILED_STATES" \
    '[.statusCheckRollup[] | select((.conclusion // .state // "") as $c | $failed | index($c)) | (.name // .context)]
     | unique | join(", ")' <<<"$pr")
  echo "$branch: $url, open ${age_hours}h, failing: ${failing:-none}"
  if [ -n "$failing" ] && [ "$age_hours" -ge 24 ]; then
    alerts+=("• <$url|$title> is still red after ${age_hours}h: $failing")
  elif [ "$age_hours" -ge 72 ]; then
    alerts+=("• <$url|$title> has waited $(( age_hours / 24 )) days for a review")
  fi
done

if [ "${#alerts[@]}" -eq 0 ]; then
  echo "No model bot PR needs attention."
  exit 0
fi

text=$(printf 'Model bot PRs need attention:\n%s' "$(printf '%s\n' "${alerts[@]}")")
payload=$(jq -n --arg text "$text" '{text: $text}')

if [ "${DRY_RUN:-false}" = "true" ]; then
  echo "Dry run, not posting:"
  echo "$payload"
  exit 0
fi

if [ -z "${SLACK_WEBHOOK_URL:-}" ]; then
  echo "::error::SLACK_WEBHOOK_URL is not set"
  exit 1
fi
curl -fsS -X POST -H 'Content-Type: application/json' --data "$payload" "$SLACK_WEBHOOK_URL"
