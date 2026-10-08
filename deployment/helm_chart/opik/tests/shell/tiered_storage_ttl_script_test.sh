#!/bin/sh
# Executable coverage for the tiered-storage TTL Job's embedded script.
#
# helm-unittest asserts rendered manifests but never runs the script, so its control
# flow (skip, idempotence, all-replica verification, failure paths) is otherwise
# untested. This renders the chart, extracts the script, and runs it against a
# mocked clickhouse-client.
#
# Usage: tests/shell/tiered_storage_ttl_script_test.sh
# Requires: helm, python3, bash. Run from the chart directory or anywhere.
set -eu

CHART_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

FAILED=0
pass() { echo "  ok   - $1"; }
fail() { echo "  FAIL - $1"; echo "         $2"; FAILED=$((FAILED + 1)); }

# --- render and extract -----------------------------------------------------
# Subchart dependencies are irrelevant to this template; render without them.
cp -R "$CHART_DIR" "$WORK/chart"
python3 - "$WORK/chart/Chart.yaml" <<'PY'
import sys, yaml
p = sys.argv[1]
d = yaml.safe_load(open(p))
d.pop("dependencies", None)
yaml.safe_dump(d, open(p, "w"), sort_keys=False)
PY

helm template opik "$WORK/chart" \
  --set clickhouse.enabled=true \
  --set clickhouse.tieredStorage.enabled=true \
  --set clickhouse.tieredStorage.ttl.enabled=true \
  --set clickhouse.tieredStorage.cold.s3.endpoint=https://s3.example/cold/ \
  --set-json 'component.backend.envFrom=[{"configMapRef":{"name":"opik-backend"}},{"secretRef":{"name":"opik-backend"}}]' \
  -s templates/clickhouse-tiered-storage-ttl-job.yaml > "$WORK/rendered.yaml"

python3 - "$WORK/rendered.yaml" "$WORK/script.sh" <<'PY'
import sys, yaml
src, dst = sys.argv[1], sys.argv[2]
for doc in yaml.safe_load_all(open(src)):
    if doc and doc.get("kind") == "Job":
        open(dst, "w").write(doc["spec"]["template"]["spec"]["containers"][0]["args"][0])
        break
else:
    raise SystemExit("no Job in rendered output")
PY

bash -n "$WORK/script.sh" || { echo "rendered script is not valid bash"; exit 1; }

# Server-side apply (ArgoCD) rejects a container env with a repeated name.
python3 - "$WORK/rendered.yaml" <<'PY'
import sys, yaml, collections
for doc in yaml.safe_load_all(open(sys.argv[1])):
    if doc and doc.get("kind") == "Job":
        names = [e["name"] for e in doc["spec"]["template"]["spec"]["containers"][0]["env"]]
        dup = [n for n, c in collections.Counter(names).items() if c > 1]
        if dup:
            raise SystemExit(f"duplicate env names: {dup}")
PY
echo "  ok   - env names are unique (server-side apply)"

# --- mocked clickhouse-client -------------------------------------------------
# Logs every query (and argv) and answers from MOCK_* state. Any query outside the
# script's contract is rejected, so a new statement cannot slip in untested.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/clickhouse-client" <<'MOCK'
#!/bin/sh
printf '%s\n' "$*" >> "$CALL_LOG"
[ -z "${MOCK_EXPECT_PASS:-}" ] || [ "$CLICKHOUSE_PASSWORD" = "$MOCK_EXPECT_PASS" ] || { echo "mock: password '$CLICKHOUSE_PASSWORD', want '$MOCK_EXPECT_PASS'" >&2; exit 65; }
[ "$1" = --host ] && [ "$2" = clickhouse-test ] || { echo "mock: expected --host clickhouse-test, got: $*" >&2; exit 64; }
[ "$3" = --query ] || { echo "mock: expected --query, got: $*" >&2; exit 64; }
q="$4"
case "$q" in
  *"FROM system.clusters"*)            echo "${MOCK_REPLICAS:-2}" ;;
  *"system.storage_policies"*)         echo "${MOCK_POLICY_REPLICAS:-${MOCK_REPLICAS:-2}}" ;;
  "EXISTS TABLE "*)                    echo "${MOCK_EXISTS:-1}" ;;
  "SELECT storage_policy FROM"*)       echo "${MOCK_POLICY:-tiered_replicated}" ;;
  "SELECT extract("*" = "*)            echo "${MOCK_SAME:-1}" ;;
  *"clusterAllReplicas(getMacro('cluster'), system.tables)"*) echo "${MOCK_OK:-${MOCK_REPLICAS:-2}}" ;;
  "ALTER TABLE "*)
    [ "${MOCK_ALTER_FAIL:-0}" = 1 ] && { echo "Code: 497. DB::Exception: Not enough privileges" >&2; exit 1; }
    echo "clickhouse-test 9000 0 0 0" ;;
  *) echo "mock: unexpected query: $q" >&2; exit 64 ;;
esac
MOCK
chmod +x "$WORK/bin/clickhouse-client"

# run VAR=value ... : runs the script with a clean, complete env plus overrides.
run() {
  CALL_LOG="$WORK/calls"; export CALL_LOG
  : > "$CALL_LOG"
  env -i PATH="$WORK/bin:$PATH" CALL_LOG="$CALL_LOG" \
    ANALYTICS_DB_HOST=clickhouse-test ANALYTICS_DB_DATABASE_NAME=opik \
    ANALYTICS_DB_MIGRATIONS_USER=opik ANALYTICS_DB_MIGRATIONS_PASS=s3cret-pass \
    "$@" bash "$WORK/script.sh" >"$WORK/out" 2>"$WORK/err"
}
alters() { grep -c "ALTER TABLE" "$CALL_LOG" || true; }

echo "tiered-storage TTL script"

if run; then
  [ "$(alters)" = 0 ] && pass "already applied: verifies, no ALTER" || fail "already applied: verifies, no ALTER" "$(alters) ALTERs"
else
  fail "already applied: verifies, no ALTER" "exited non-zero: $(cat "$WORK/err")"
fi

if run MOCK_POLICY=default MOCK_SAME=0; then
  grep -q "ON CLUSTER '{cluster}' MODIFY SETTING storage_policy = 'tiered_replicated'" "$CALL_LOG" \
    && grep -q "ON CLUSTER '{cluster}' MODIFY TTL id_at + INTERVAL 13 WEEK TO DISK 'cold' SETTINGS materialize_ttl_after_modify = 0" "$CALL_LOG" \
    && pass "fresh table: attaches the policy and sets the TTL without materializing" \
    || fail "fresh table: attaches the policy and sets the TTL without materializing" "$(grep ALTER "$CALL_LOG")"
else
  fail "fresh table: attaches the policy and sets the TTL" "exited non-zero: $(cat "$WORK/err")"
fi
grep -q "MATERIALIZE TTL" "$CALL_LOG" && fail "never materializes the TTL" "issued MATERIALIZE TTL" || pass "never materializes the TTL"
grep -q "s3cret-pass" "$CALL_LOG" && fail "password stays off argv" "found on argv" || pass "password stays off argv"

if run MOCK_EXISTS=0; then
  [ "$(alters)" = 0 ] && grep -q "skip opik.traces_local" "$WORK/out" \
    && pass "missing table: skipped, no ALTER" || fail "missing table: skipped, no ALTER" "$(cat "$WORK/out")"
else
  fail "missing table: skipped" "exited non-zero"
fi

run MOCK_EXISTS=garbage && fail "unreadable EXISTS fails the Job" "exited 0" || pass "unreadable EXISTS fails the Job"

if run MOCK_POLICY_REPLICAS=1 MOCK_POLICY=default; then
  fail "policy missing on a replica fails before any ALTER" "exited 0"
else
  [ "$(alters)" = 0 ] && pass "policy missing on a replica fails before any ALTER" || fail "policy missing on a replica fails before any ALTER" "$(alters) ALTERs"
fi

run MOCK_OK=1 && fail "a replica that does not match fails the Job" "exited 0" || pass "a replica that does not match fails the Job"

run MOCK_POLICY=default MOCK_ALTER_FAIL=1 && fail "a failed ALTER fails the Job" "exited 0" || pass "a failed ALTER fails the Job"

run ANALYTICS_DB_MIGRATIONS_PASS= SRC0_ANALYTICS_DB_MIGRATIONS_PASS=cm-pass SRC1_ANALYTICS_DB_MIGRATIONS_PASS=secret-pass MOCK_EXPECT_PASS=secret-pass \
  && pass "a later source (the Secret) wins over the ConfigMap" || fail "a later source (the Secret) wins over the ConfigMap" "$(cat "$WORK/err")"
run ANALYTICS_DB_MIGRATIONS_PASS= SRC0_ANALYTICS_DB_MIGRATIONS_PASS=cm-pass SRC1_ANALYTICS_DB_MIGRATIONS_PASS= MOCK_EXPECT_PASS=cm-pass \
  && pass "a source without the key falls back to the earlier one" || fail "a source without the key falls back to the earlier one" "$(cat "$WORK/err")"

run ANALYTICS_DB_MIGRATIONS_PASS= && fail "missing credential fails before any query" "exited 0" \
  || { [ -s "$CALL_LOG" ] && fail "missing credential fails before any query" "queried anyway" || pass "missing credential fails before any query"; }

[ "$FAILED" -eq 0 ] || { echo "$FAILED failed"; exit 1; }
echo "all passed"
