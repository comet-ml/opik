#!/usr/bin/env bash
# Tests for run_db_migrations.sh. Stubs `java` on PATH so the real script runs without a JVM or a
# database. Run from anywhere: apps/opik-backend/test_run_db_migrations.sh
#
# The script is the `backend-migrations` init container of the Helm chart and the first step of the
# docker-compose backend command, so its exit code decides whether the backend starts.
set -euo pipefail

cd "$(dirname "$0")"
SCRIPT="$PWD/run_db_migrations.sh"

fails=0
pass() { echo "  ok: $1"; }
fail() {
	echo "  FAIL: $1"
	shift
	printf '    %s\n' "$@"
	fails=$((fails + 1))
}

stub_dir="$(mktemp -d)"
trap 'rm -rf "$stub_dir"' EXIT
export PATH="$stub_dir:$PATH"
export OPIK_VERSION="0.0.0-test"
calls="$stub_dir/calls"

# `java -jar <jar> <command> config.yml`: records the command and exits with the code set for it
# in STATE_EXIT (db migrate) or ANALYTICS_EXIT (dbAnalytics migrate).
cat >"$stub_dir/java" <<'STUB'
#!/usr/bin/env bash
echo "$3" >>"$CALLS_FILE"
case "$3" in
db) exit "${STATE_EXIT:-0}" ;;
dbAnalytics) exit "${ANALYTICS_EXIT:-0}" ;;
esac
STUB
chmod +x "$stub_dir/java"
export CALLS_FILE="$calls"

# expect <name> <state-exit> <analytics-exit> <want-exit> <want-calls> <want-text>
expect() {
	local name="$1" state="$2" analytics="$3" want_exit="$4" want_calls="$5" want_text="$6" out status
	: >"$calls"
	set +e
	out="$(STATE_EXIT="$state" ANALYTICS_EXIT="$analytics" bash "$SCRIPT" 2>&1)"
	status=$?
	set -e
	if [[ "$status" != "$want_exit" ]]; then
		fail "$name" "expected exit $want_exit, got $status" "output: $out"
		return
	fi
	if [[ "$(tr '\n' ' ' <"$calls")" != "$want_calls" ]]; then
		fail "$name" "expected migrations run: $want_calls" "actual: $(tr '\n' ' ' <"$calls")"
		return
	fi
	if ! printf '%s' "$out" | grep -qF -- "$want_text"; then
		fail "$name" "expected output to contain: $want_text" "actual: $out"
		return
	fi
	pass "$name"
}

expect "both migrations succeed" 0 0 0 "db dbAnalytics " "completed successfully"
expect "db migrate fails: exits non-zero, still runs dbAnalytics" 1 0 1 "db dbAnalytics " "db migrate: exit 1"
expect "dbAnalytics migrate fails: exits non-zero" 0 3 1 "db dbAnalytics " "dbAnalytics migrate: exit 3"
expect "both fail: exits non-zero" 2 3 1 "db dbAnalytics " "db migrate: exit 2, dbAnalytics migrate: exit 3"

if ((fails > 0)); then
	echo "$fails failed"
	exit 1
fi
echo "all passed"
