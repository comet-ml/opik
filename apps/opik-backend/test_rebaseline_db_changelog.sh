#!/usr/bin/env bash
# Tests for rebaseline_db_changelog.sh. Stubs `java` and `curl` on PATH so the real script runs
# end to end — arg parsing, the schema-at-head guard, the fingerprint drift compare, the post-sync
# assertions — without a JVM or a ClickHouse. Run from anywhere: apps/opik-backend/test_rebaseline_db_changelog.sh
#
# The container test (ChangelogRebaselineTest) covers the Liquibase side: that changelogSync really
# restores the ledger without executing DDL. What it cannot cover is this script's own logic, which
# is where an operator-facing refusal either happens or silently does not.
set -euo pipefail

cd "$(dirname "$0")"
SCRIPT="$PWD/rebaseline_db_changelog.sh"

fails=0
pass() { echo "  ok: $1"; }
fail() {
	echo "  FAIL: $1"
	shift
	printf '    %s\n' "$@"
	fails=$((fails + 1))
}

# expect <name> <expected-exit> <expected-substring> -- <script args...>
# Runs the script under the current stubs and asserts both exit code and output.
expect() {
	local name="$1" want_exit="$2" want_text="$3" out status
	shift 4 # name, exit, text, and the literal --
	set +e
	out="$("$SCRIPT" "$@" 2>&1)"
	status=$?
	set -e
	if [[ "$status" != "$want_exit" ]]; then
		fail "$name" "expected exit $want_exit, got $status" "output: $out"
		return
	fi
	if [[ -n "$want_text" ]] && ! printf '%s' "$out" | grep -qF -- "$want_text"; then
		fail "$name" "expected output to contain: $want_text" "actual: $out"
		return
	fi
	pass "$name"
}

stub_dir="$(mktemp -d)"
trap 'rm -rf "$stub_dir"' EXIT
export PATH="$stub_dir:$PATH"

# The script requires a jar next to it named for $OPIK_VERSION, and a config file. Point it at a
# scratch working directory holding fakes, so the real ones are never touched.
work="$stub_dir/work"
mkdir -p "$work"
export OPIK_VERSION="0.0.0-test"
touch "$work/opik-backend-$OPIK_VERSION.jar" "$work/config.yml"
cd "$work"

export ANALYTICS_DB_MIGRATIONS_URL="jdbc:clickhouse://stub:8123/opik"
export ANALYTICS_DB_MIGRATIONS_USER="opik"
export ANALYTICS_DB_MIGRATIONS_PASS="opik"
export ANALYTICS_DB_DATABASE_NAME="opik"

# `java` stub. PENDING controls how many changeset identities the fast-forward dry run emits, so a
# test can present a wiped ledger (many pending) or a clean one (none). The real `fast-forward
# --all` clears the pending set, so the stub models that by dropping to zero afterwards — which is
# what lets the post-sync check pass on the happy path. STICKY_PENDING=1 suppresses that, to
# simulate a sync that did not actually clear the ledger.
# Writes a marker per invocation so a test can assert whether the write was reached.
cat >"$stub_dir/java" <<'STUB'
#!/usr/bin/env bash
args="$*"
echo "$args" >>"${STUB_JAVA_LOG:?}"
synced_flag="${STUB_JAVA_LOG}.synced"
# Liquibase prints a substantial report either way; only the INSERT lines matter to the parser.
echo "Liquibase Community stub"
echo "Run:                          0"
case "$args" in
*"fast-forward --all --dry-run"*)
	pending="${PENDING:-0}"
	if [ -e "$synced_flag" ] && [ "${STICKY_PENDING:-0}" != "1" ]; then
		pending=0
	fi
	# DRIFT=1 models the ledger being modified by something else between the set the operator
	# reviewed and the re-check immediately before the write: the identities shift on every dry-run
	# after the first, so the two fingerprints differ without the count changing.
	offset=0
	if [ "${DRIFT:-0}" = "1" ]; then
		dry_count_flag="${STUB_JAVA_LOG}.dryruns"
		echo x >>"$dry_count_flag"
		runs=$(wc -l <"$dry_count_flag")
		[ "$runs" -gt 1 ] && offset=1000
	fi
	i=1
	while [ "$i" -le "$pending" ]; do
		echo "INSERT INTO default.DATABASECHANGELOG (ID, AUTHOR, FILENAME) VALUES ('$((i + offset))', 'someone', 'migration_$((i + offset)).sql');"
		i=$((i + 1))
	done
	;;
*"fast-forward --all"*)
	touch "$synced_flag"
	echo "changelogSync applied"
	;;
*status*) echo "status report" ;;
esac
STUB
chmod +x "$stub_dir/java"

# `curl` stub returning $TABLE_COUNT, or failing when TABLE_COUNT is 'unreachable'. Once the sync
# has run, TABLE_COUNT_AFTER (when set) takes over, so a test can simulate DDL having executed or
# the post-sync probe failing ('unreachable'). Also records the URL it was asked for, so a test can
# assert what the JDBC parser resolved to.
cat >"$stub_dir/curl" <<'STUB'
#!/usr/bin/env bash
prev=""
query="" param_db=""
for arg in "$@"; do
	case "$arg" in
	http://* | https://*) echo "$arg" >>"${STUB_CURL_URLS:?}" ;;
	query=*) query="${arg#query=}" ;;
	param_db=*) param_db="${arg#param_db=}" ;;
	esac
	[ "$prev" = "-u" ] && echo "$arg" >>"${STUB_CURL_CREDS:?}"
	prev="$arg"
done

# Assert the probe's contract with ClickHouse, not just that some request happened: the count must
# be scoped to a bound database parameter. A probe that dropped param_db, inlined the name, or
# counted something other than system.tables would otherwise pass every test in this suite.
{
	printf 'query=%s\n' "$query"
	printf 'param_db=%s\n' "$param_db"
} >>"${STUB_CURL_QUERY:?}"
case "$query" in
*"system.tables"*"database = {db:String}"*) ;;
*)
	echo "STUB-CONTRACT-VIOLATION: query did not count system.tables by bound db param: $query" >&2
	exit 90
	;;
esac
if [ -z "$param_db" ]; then
	echo "STUB-CONTRACT-VIOLATION: param_db was not sent" >&2
	exit 90
fi
after=""
if [ -e "${STUB_JAVA_LOG:-}.synced" ] && [ -n "${TABLE_COUNT_AFTER:-}" ]; then
	after="$TABLE_COUNT_AFTER"
fi
effective="${after:-${TABLE_COUNT:-0}}"
if [ "$effective" = "unreachable" ]; then
	echo "curl: (7) Failed to connect" >&2
	exit 7
fi
echo "$effective"
STUB
chmod +x "$stub_dir/curl"

reset_log() {
	STUB_JAVA_LOG="$stub_dir/java.log"
	STUB_CURL_URLS="$stub_dir/curl-urls.log"
	STUB_CURL_CREDS="$stub_dir/curl-creds.log"
	STUB_CURL_QUERY="$stub_dir/curl-query.log"
	export STUB_JAVA_LOG STUB_CURL_URLS STUB_CURL_CREDS STUB_CURL_QUERY
	: >"$STUB_JAVA_LOG"
	: >"$STUB_CURL_URLS"
	: >"$STUB_CURL_CREDS"
	: >"$STUB_CURL_QUERY"
	rm -f "$STUB_JAVA_LOG.synced" "$STUB_JAVA_LOG.dryruns"
}
wrote_ledger() { grep -qE 'fast-forward --all [^-]' "$STUB_JAVA_LOG"; }

echo "argument handling"
reset_log
export PENDING=0 TABLE_COUNT=27
expect "--help exits 0" 0 "Usage:" -- --help
expect "unknown flag exits 2" 2 "Unknown option" -- --nope
expect "invalid --database exits 2" 2 "must be 'dbAnalytics'" -- --database bogus

echo
echo "schema-at-head guard (the reviewer's bricking case)"
# An empty database against a full pending list is not a lost ledger over an intact schema — it is
# a database with no schema. Re-baselining strands it there permanently, so this must refuse.
reset_log
PENDING=149 TABLE_COUNT=0 expect "refuses an empty schema" 1 "Refusing to re-baseline" -- --yes
if wrote_ledger; then
	fail "refuses an empty schema before writing" "the script reached 'fast-forward --all'"
else
	pass "refuses an empty schema before writing"
fi

reset_log
PENDING=149 TABLE_COUNT=5 expect "refuses a nearly empty schema" 1 "Refusing to re-baseline" -- --yes

# --yes is the unattended path; the guard must hold there too, which is what makes it a guard
# rather than a prompt.
reset_log
PENDING=149 TABLE_COUNT=0 expect "--yes does not bypass the guard" 1 "Refusing to re-baseline" -- --yes

# The refusal above tells the operator to re-run with --force-unverified, so the flag must actually
# work on THIS branch — dbAnalytics, default config, a real (low) count. The two forced cases
# elsewhere in this file exit on earlier branches (unreachable probe, --database db) and so never
# reach it; without this case the flag was inert here while the message advertised it.
reset_log
PENDING=149 TABLE_COUNT=0 \
	expect "--force-unverified overrides a low table count" 0 "Proceeding unverified" -- --yes --force-unverified
if wrote_ledger; then
	pass "--force-unverified past a low count writes the ledger"
else
	fail "--force-unverified past a low count writes the ledger" "never reached 'fast-forward --all'"
fi
reset_log
PENDING=149 TABLE_COUNT=0 \
	expect "--force-unverified past a low count completes" 0 "Re-baseline complete" -- --yes --force-unverified

echo
echo "the verified happy path still completes"
# The reviewer's measured good case: intact schema (27 tables), wiped ledger (149 pending).
reset_log
PENDING=149 TABLE_COUNT=27 expect "re-baselines an intact schema" 0 "Re-baseline complete" -- --yes
if wrote_ledger; then
	pass "re-baselines an intact schema by writing the ledger"
else
	fail "re-baselines an intact schema by writing the ledger" "never reached 'fast-forward --all'"
fi

echo
echo "unverifiable schemas"
reset_log
PENDING=149 TABLE_COUNT=unreachable expect "refuses when ClickHouse is unreachable" 2 "Could not read the table count" -- --yes
reset_log
PENDING=149 TABLE_COUNT=unreachable expect "--force-unverified overrides an unreachable probe" 0 "Proceeding unverified" -- --yes --force-unverified

# MySQL has no client in the image, so its precondition cannot be checked at all.
reset_log
PENDING=149 TABLE_COUNT=0 expect "refuses --database db unforced" 2 "no MySQL client" -- --database db --yes
if wrote_ledger; then
	fail "refuses --database db before writing" "the script reached 'fast-forward --all'"
else
	pass "refuses --database db before writing"
fi
# An unverifiable setup is a property of the invocation, not the schema, so it must refuse before any
# database call — otherwise an unreachable server produces a connect stack trace from `status`
# instead of this refusal, and the guard's intent is not what the operator sees.
if [[ -s "$STUB_JAVA_LOG" ]]; then
	fail "refuses --database db before contacting the database" \
		"java was invoked: $(head -1 "$STUB_JAVA_LOG")"
else
	pass "refuses --database db before contacting the database"
fi
reset_log
PENDING=149 TABLE_COUNT=0 expect "--database db proceeds when forced" 0 "Re-baseline complete" -- --database db --yes --force-unverified

echo
echo "dry run"
reset_log
PENDING=149 TABLE_COUNT=27 expect "dry run reports without writing" 0 "Dry run" -- --dry-run
if wrote_ledger; then
	fail "dry run leaves the ledger untouched" "the script reached 'fast-forward --all'"
else
	pass "dry run leaves the ledger untouched"
fi

# A dry run against an empty schema should still report the refusal — an operator inspecting before
# committing is exactly who needs to hear it.
reset_log
PENDING=149 TABLE_COUNT=0 expect "dry run surfaces the refusal" 1 "Refusing to re-baseline" -- --dry-run

echo
echo "migrations URL parsing"
# The driver's only contract is that 'jdbc:clickhouse:' prefixes the rest, so the remainder is
# either '//host:port' or an embedded-protocol 'https://host:port'. Both reach the probe, and a
# fixed '//'-strip would turn the latter into a host of 'jdbc:clickhouse:https:'.
# expect_url <name> <migrations-url> <expected-probe-url>
expect_url() {
	local name="$1" url="$2" want="$3" got
	reset_log
	ANALYTICS_DB_MIGRATIONS_URL="$url" PENDING=0 TABLE_COUNT=27 "$SCRIPT" --dry-run >/dev/null 2>&1 || true
	got="$(head -1 "$STUB_CURL_URLS" 2>/dev/null || true)"
	if [[ "$got" == "$want" ]]; then
		pass "$name"
	else
		fail "$name" "expected probe URL: $want" "actual: ${got:-<none>}"
	fi
}
expect_url "bare host:port (helm, compose)" "jdbc:clickhouse://clickhouse:8123" "http://clickhouse:8123/"
expect_url "path suffix (packaged config default)" "jdbc:clickhouse://localhost:8123/opik" "http://localhost:8123/"
expect_url "query parameters, no path" "jdbc:clickhouse://h:8123?compress=1" "http://h:8123/"
expect_url "ssl=true implies https" "jdbc:clickhouse://h.example.com:8443/opik?ssl=true" "https://h.example.com:8443/"
expect_url "ssl=true with an embedded protocol" "jdbc:clickhouse:https://secure.example.com:9440/opik" "https://secure.example.com:9440/"
expect_url "embedded https protocol" "jdbc:clickhouse:https://host:8443/opik" "https://host:8443/"
expect_url "embedded http protocol" "jdbc:clickhouse:http://host:8123/opik" "http://host:8123/"

# Prefixes the legacy migrations driver does NOT accept must refuse here too. Verifying a database
# Liquibase cannot connect to would bless a schema the write never reaches.
# expect_rejected_url <name> <migrations-url>
# An empty curl log alone would also be satisfied by the script dying for an unrelated reason, so
# assert the whole boundary: no probe attempted, AND the guard's own exit 2 and message.
expect_rejected_url() {
	local name="$1" url="$2" out status probed
	reset_log
	set +e
	out="$(ANALYTICS_DB_MIGRATIONS_URL="$url" PENDING=149 TABLE_COUNT=27 "$SCRIPT" --yes 2>&1)"
	status=$?
	set -e
	probed="$(head -1 "$STUB_CURL_URLS" 2>/dev/null || true)"
	if [[ -n "$probed" ]]; then
		fail "$name" "expected no probe to be attempted" "probed: $probed"
	elif [[ "$status" != 2 ]]; then
		fail "$name" "expected exit 2 from the unverifiable guard, got $status" "output: $out"
	elif ! printf '%s' "$out" | grep -qF "Could not read the table count"; then
		fail "$name" "expected the 'Could not read the table count' refusal" "output: $out"
	elif wrote_ledger; then
		fail "$name" "the script reached 'fast-forward --all'"
	else
		pass "$name"
	fi
}

# Prefixes the legacy migrations driver does not accept, and portless URLs it rejects with "port is
# missed or wrong" — probing either would verify a database the write never reaches.
expect_rejected_url "rejects the jdbc:ch alias" "jdbc:ch:https://host:8443"
expect_rejected_url "rejects jdbc:clickhouses:" "jdbc:clickhouses://secure.example.com:9440/opik"
expect_rejected_url "rejects a portless URL" "jdbc:clickhouse://clickhouse/opik"
expect_rejected_url "rejects a portless URL with ssl=true" "jdbc:clickhouse://host/opik?ssl=true"

# An unparseable URL must refuse, not probe a garbage host — the count gates a destructive write.
reset_log
ANALYTICS_DB_MIGRATIONS_URL="not-a-jdbc-url" PENDING=149 TABLE_COUNT=27 \
	expect "refuses an unparseable migrations URL" 2 "Could not read the table count" -- --yes

# A default-only deployment sets none of the ANALYTICS_DB_MIGRATIONS_* variables, and Liquibase
# still connects through config.yml's per-variable fallbacks. The probe must resolve the same ones,
# or it refuses a recovery that is perfectly safe. Each variable falls back independently, so an
# omitted password must not leave the probe authenticating as empty while Liquibase uses 'opik'.
reset_log
(
	unset ANALYTICS_DB_MIGRATIONS_URL
	PENDING=0 TABLE_COUNT=27 "$SCRIPT" --dry-run >/dev/null 2>&1 || true
)
got="$(head -1 "$STUB_CURL_URLS" 2>/dev/null || true)"
if [[ "$got" == "http://localhost:8123/" ]]; then
	pass "unset URL falls back to the config.yml default"
else
	fail "unset URL falls back to the config.yml default" \
		"expected probe URL: http://localhost:8123/" "actual: ${got:-<none>}"
fi

reset_log
(
	unset ANALYTICS_DB_MIGRATIONS_USER ANALYTICS_DB_MIGRATIONS_PASS ANALYTICS_DB_DATABASE_NAME
	PENDING=0 TABLE_COUNT=27 "$SCRIPT" --dry-run >/dev/null 2>&1 || true
)
creds="$(head -1 "$STUB_CURL_CREDS" 2>/dev/null || true)"
if [[ "$creds" == "opik:opik" ]]; then
	pass "unset credentials fall back to the config.yml defaults"
else
	fail "unset credentials fall back to the config.yml defaults" \
		"expected -u opik:opik" "actual: ${creds:-<none>}"
fi

reset_log
set +e
(
	unset ANALYTICS_DB_MIGRATIONS_URL ANALYTICS_DB_MIGRATIONS_USER ANALYTICS_DB_MIGRATIONS_PASS ANALYTICS_DB_DATABASE_NAME
	PENDING=149 TABLE_COUNT=27 "$SCRIPT" --yes >/dev/null 2>&1
)
default_only_status=$?
set -e
if [[ "$default_only_status" == 0 ]]; then
	pass "a default-only deployment recovers without --force-unverified"
else
	fail "a default-only deployment recovers without --force-unverified" \
		"expected exit 0, got $default_only_status"
fi

echo
echo "probe contract"
# The stub fails the run outright on a malformed probe (see STUB-CONTRACT-VIOLATION), so every
# passing test above already depends on this. Assert it directly too, including that the database
# is sent as a bound parameter rather than inlined, and that it tracks ANALYTICS_DB_DATABASE_NAME.
reset_log
PENDING=0 TABLE_COUNT=27 ANALYTICS_DB_DATABASE_NAME="custom_db" \
	"$SCRIPT" --dry-run >/dev/null 2>&1 || true
sent_query="$(grep '^query=' "$STUB_CURL_QUERY" | head -1 || true)"
sent_db="$(grep '^param_db=' "$STUB_CURL_QUERY" | head -1 || true)"
if [[ "$sent_query" == *"count() FROM system.tables"* && "$sent_query" == *"database = {db:String}"* ]]; then
	pass "probe counts system.tables via a bound db parameter"
else
	fail "probe counts system.tables via a bound db parameter" "actual: ${sent_query:-<none>}"
fi
if [[ "$sent_db" == "param_db=custom_db" ]]; then
	pass "probe scopes the count to ANALYTICS_DB_DATABASE_NAME"
else
	fail "probe scopes the count to ANALYTICS_DB_DATABASE_NAME" \
		"expected param_db=custom_db" "actual: ${sent_db:-<none>}"
fi

echo
echo "non-default config"
# The write connects through CONFIG; the probe reads the ANALYTICS_DB_MIGRATIONS_* env vars. Those
# describe the same database only because the packaged config.yml resolves from exactly those vars.
# A different config breaks the equivalence, so verifying could bless a database the write never
# touches — the guard failing open. It must refuse instead.
touch "$work/custom.yml"
reset_log
PENDING=149 TABLE_COUNT=27 expect "refuses a non-default --config" 2 "non-default config" -- --config custom.yml --yes
if wrote_ledger; then
	fail "refuses a non-default --config before writing" "the script reached 'fast-forward --all'"
else
	pass "refuses a non-default --config before writing"
fi
# The probe authenticates with the migration credentials against the endpoint the env vars name.
# Under a config we are about to reject, that endpoint is not necessarily one this invocation is
# entitled to contact, so the refusal has to come first — nothing may be sent before it.
if [[ -s "$STUB_CURL_CREDS" || -s "$STUB_CURL_URLS" ]]; then
	fail "sends no credentials before refusing a non-default --config" \
		"probed: $(head -1 "$STUB_CURL_URLS" 2>/dev/null)" \
		"credentials: $(head -1 "$STUB_CURL_CREDS" 2>/dev/null)"
else
	pass "sends no credentials before refusing a non-default --config"
fi
# Forcing past the refusal must not re-enable the probe either — the config is still untrusted.
reset_log
PENDING=149 TABLE_COUNT=27 \
	"$SCRIPT" --config custom.yml --yes --force-unverified >/dev/null 2>&1 || true
if [[ -s "$STUB_CURL_CREDS" ]]; then
	fail "sends no credentials under a forced non-default --config" \
		"credentials: $(head -1 "$STUB_CURL_CREDS")"
else
	pass "sends no credentials under a forced non-default --config"
fi
reset_log
PENDING=149 TABLE_COUNT=27 \
	expect "--force-unverified allows a non-default --config" 0 "Proceeding unverified" -- --config custom.yml --yes --force-unverified
# Passing the default path explicitly is still the packaged config, so it must not trip the guard.
reset_log
PENDING=149 TABLE_COUNT=27 expect "an explicit default --config still verifies" 0 "Re-baseline complete" -- --config config.yml --yes

echo
echo "pending-set drift"
# The operator confirms against the set they were shown, so the script re-fingerprints immediately
# before writing and aborts if the ledger moved underneath — another recovery, or a deployment
# applying migrations. Without these cases the comparison could be deleted outright and the suite
# would stay green, since every other test leaves the pending set identical across both dry-runs.
reset_log
DRIFT=1 PENDING=149 TABLE_COUNT=27 \
	expect "aborts when the pending set changed while waiting" 1 "pending changesets changed" -- --yes
if wrote_ledger; then
	fail "drift aborts before writing" "the script reached 'fast-forward --all'"
else
	pass "drift aborts before writing"
fi

# --force-unverified asserts the schema is at head; it says nothing about the ledger holding still,
# so drift must abort on that path too.
reset_log
DRIFT=1 PENDING=149 TABLE_COUNT=0 \
	expect "drift aborts even under --force-unverified" 1 "pending changesets changed" -- --yes --force-unverified
if wrote_ledger; then
	fail "drift aborts before writing when forced" "the script reached 'fast-forward --all'"
else
	pass "drift aborts before writing when forced"
fi

echo
echo "post-sync verification"
# `status` exits 0 whether or not changesets remain, so the script re-checks the fingerprint.
# STICKY_PENDING holds the pending set nonzero across the write, simulating a sync that reported
# success without clearing the ledger — the script must fail rather than claim completion.
reset_log
PENDING=149 TABLE_COUNT=27 STICKY_PENDING=1 \
	expect "fails when changesets remain pending" 1 "Re-baseline did not complete" -- --yes

# changelogSync writes ledger rows only, so a changed table count means DDL ran.
reset_log
PENDING=149 TABLE_COUNT=27 TABLE_COUNT_AFTER=31 \
	expect "fails when the table count changed across the sync" 1 "table count changed" -- --yes

# A post-sync probe that cannot connect is not a schema that shrank. The ledger is already written
# by this point, so this must warn and complete rather than claiming DDL ran and failing.
reset_log
PENDING=149 TABLE_COUNT=27 TABLE_COUNT_AFTER=unreachable \
	expect "a failed post-sync probe warns without claiming drift" 0 "could not be confirmed" -- --yes
reset_log
PENDING=149 TABLE_COUNT=27 TABLE_COUNT_AFTER=unreachable \
	expect "a failed post-sync probe still reports completion" 0 "Re-baseline complete" -- --yes

echo
if [[ "$fails" -gt 0 ]]; then
	echo "$fails test(s) failed"
	exit 1
fi
echo "all tests passed"
