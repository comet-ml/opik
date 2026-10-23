#!/usr/bin/env bash
# gh and curl are stubbed on PATH, so this needs no token and posts nothing.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
fails=0
check() {
	if printf '%s' "$3" | grep -qF -- "$2"; then
		echo "  ok: $1"
	else
		echo "  FAIL: $1"
		echo "    expected to contain: $2"
		echo "    actual: $3"
		fails=$((fails + 1))
	fi
}
check_absent() {
	if printf '%s' "$3" | grep -qF -- "$2"; then
		echo "  FAIL: $1 (did not expect: $2)"
		fails=$((fails + 1))
	else
		echo "  ok: $1"
	fi
}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin" "$work/prs"

# gh answers `pr list --head <branch> --jq <filter>` from the fixture for that branch, as gh would after --jq.
cat >"$work/bin/gh" <<'EOF'
#!/usr/bin/env bash
head="" filter="."
while [ $# -gt 0 ]; do
	case "$1" in
	--head) head="$2"; shift ;;
	--jq) filter="$2"; shift ;;
	esac
	shift
done
fixture="$FIXTURES/${head//\//_}.json"
if [ -f "$fixture" ]; then jq -c "$filter" "$fixture"; else echo '[]' | jq -c "$filter"; fi
EOF
cat >"$work/bin/curl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$@" >>"$CURL_LOG"
EOF
chmod +x "$work/bin/gh" "$work/bin/curl"
export PATH="$work/bin:$PATH" FIXTURES="$work/prs" CURL_LOG="$work/curl.log" REPO=comet-ml/opik GH_TOKEN=stub

hours_ago() { jq -rn --argjson h "$1" '(now - $h * 3600) | todate'; }
pr() { # pr <branch> <hours-open> <checks-json> [cross-repo] [title]
	jq -n --arg created "$(hours_ago "$2")" --argjson checks "$3" --argjson cross "${4:-false}" \
		--arg title "${5:-[NA] [BE][FE] chore: sync provider model definitions}" \
		'[{number: 1, title: $title, url: "https://github.com/comet-ml/opik/pull/1", createdAt: $created,
		  isCrossRepository: $cross, statusCheckRollup: $checks}]' >"$FIXTURES/${1//\//_}.json"
}
run() {
	: >"$CURL_LOG"
	env BOT_BRANCHES="$1" "${@:2}" bash .github/scripts/model-bot-pr-alert.sh 2>&1 || true
}

RED='[{"name":"Test on Node 20","conclusion":"FAILURE"},{"name":"Unit Tests","conclusion":"SUCCESS"}]'
PENDING='[{"name":"Unit Tests","conclusion":"","status":"IN_PROGRESS"}]'
GREEN='[{"name":"Unit Tests","conclusion":"SUCCESS"},{"context":"Baz Reviewer","state":"SUCCESS"}]'
CANCELLED='[{"name":"Unit Tests","conclusion":"CANCELLED"}]'
STATUS_RED='[{"context":"Baz Reviewer","state":"ERROR"}]'

echo "model-bot-pr-alert"
pr bot/red 30 "$RED"
run bot/red SLACK_WEBHOOK_URL=https://hooks.example >/dev/null
check "red for a day alerts" "still red after 30h: Test on Node 20" "$(cat "$CURL_LOG")"
check "the alert goes to the webhook" "https://hooks.example" "$(cat "$CURL_LOG")"

pr bot/fresh-red 5 "$RED"
out=$(run bot/fresh-red SLACK_WEBHOOK_URL=https://hooks.example)
check "red for under a day waits" "No model bot PR needs attention" "$out"
check_absent "nothing posted for a fresh red PR" "hooks.example" "$(cat "$CURL_LOG")"

pr bot/pending 30 "$PENDING"
check "running checks are not red" "No model bot PR needs attention" "$(run bot/pending SLACK_WEBHOOK_URL=https://hooks.example)"

pr bot/waiting 80 "$GREEN"
check "green for three days alerts" "has waited 3 days for a review" "$(run bot/waiting DRY_RUN=true)"

pr bot/green 50 "$GREEN"
check "green for two days waits" "No model bot PR needs attention" "$(run bot/green DRY_RUN=true)"

pr bot/cancelled 30 "$CANCELLED"
check "a cancelled check counts as red" "still red after 30h: Unit Tests" "$(run bot/cancelled DRY_RUN=true)"

pr bot/status 30 "$STATUS_RED"
check "a failed commit status counts as red" "still red after 30h: Baz Reviewer" "$(run bot/status DRY_RUN=true)"

pr bot/fork 30 "$RED" true
check "a fork PR on the same branch name is ignored" "No open PR on bot/fork" "$(run bot/fork DRY_RUN=true)"

pr bot/mention 30 "$RED" false '<!channel> & co'
out=$(run bot/mention DRY_RUN=true)
check "the title is escaped for Slack" '&lt;!channel&gt; &amp; co' "$out"
check_absent "no raw mention reaches Slack" '<!channel>' "$out"

out=$(run bot/red DRY_RUN=true SLACK_WEBHOOK_URL=https://hooks.example)
check "a dry run prints the message" "Dry run, not posting" "$out"
check_absent "a dry run posts nothing" "hooks.example" "$(cat "$CURL_LOG")"

check "no webhook outside a dry run is an error" "SLACK_WEBHOOK_URL is not set" "$(run bot/red SLACK_WEBHOOK_URL=)"

if [ "$fails" -gt 0 ]; then
	echo "$fails check(s) failed"
	exit 1
fi
echo "all checks passed"
