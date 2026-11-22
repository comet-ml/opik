#!/bin/sh
# Executable coverage for the tiered-storage TTL Job's script (files/clickhouse-tiered-storage-ttl.sh).
#
# helm-unittest asserts rendered manifests but never runs the script, so its control
# flow (skip, idempotence, all-replica verification, backfill, failure paths) is otherwise
# untested. This checks the rendered Job carries the file and its config, then runs the
# file against a mocked clickhouse-client.
#
# Usage: tests/shell/tiered_storage_ttl_script_test.sh
# Requires: helm, python3, bash. Run from the chart directory or anywhere.
set -eu

CHART_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
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

SCRIPT="$CHART_DIR/files/clickhouse-tiered-storage-ttl.sh"
python3 - "$WORK/rendered.yaml" "$SCRIPT" <<'PY'
import sys, yaml
src, script = sys.argv[1], sys.argv[2]
for doc in yaml.safe_load_all(open(src)):
    if doc and doc.get("kind") == "Job":
        c = doc["spec"]["template"]["spec"]["containers"][0]
        if c["args"][0].rstrip("\n") != open(script).read().rstrip("\n"):
            raise SystemExit("rendered args differ from files/clickhouse-tiered-storage-ttl.sh")
        env = {e["name"]: e.get("value") for e in c["env"]}
        want = {"SOURCE_COUNT": "2", "TTL_TABLES": "traces_local:13", "TTL_MATERIALIZE": "false"}
        got = {k: env.get(k) for k in want}
        if got != want:
            raise SystemExit(f"rendered config env {got}, want {want}")
        break
else:
    raise SystemExit("no Job in rendered output")
PY
echo "  ok   - the Job runs the script file with its config env"

bash -n "$SCRIPT" || { echo "script is not valid bash"; exit 1; }

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
  *"clusterAllReplicas(getMacro('cluster'), system.parts)"*) echo "${MOCK_PENDING:-0}" ;;
  *"clusterAllReplicas(getMacro('cluster'), system.mutations)"*) echo "${MOCK_RUNNING:-0}" ;;
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
    SOURCE_COUNT=2 TTL_TABLES=traces_local:13 TTL_MATERIALIZE=false \
    "$@" bash "$SCRIPT" >"$WORK/out" 2>"$WORK/err"
}
alters() { grep -c "ALTER TABLE" "$CALL_LOG" || true; }
materialized() { grep -q "ON CLUSTER '{cluster}' MATERIALIZE TTL" "$CALL_LOG"; }

echo "tiered-storage TTL script"

if run; then
  if [ "$(alters)" = 0 ]; then pass "already applied: verifies, no ALTER"
  else fail "already applied: verifies, no ALTER" "$(alters) ALTERs"; fi
else
  fail "already applied: verifies, no ALTER" "exited non-zero: $(cat "$WORK/err")"
fi

if run MOCK_POLICY=default MOCK_SAME=0; then
  if grep -q "ON CLUSTER '{cluster}' MODIFY SETTING storage_policy = 'tiered_replicated'" "$CALL_LOG" \
    && grep -q "ON CLUSTER '{cluster}' MODIFY TTL id_at + INTERVAL 13 WEEK TO DISK 'cold' SETTINGS materialize_ttl_after_modify = 0" "$CALL_LOG"; then
    pass "fresh table: attaches the policy and sets the TTL without materializing"
  else
    fail "fresh table: attaches the policy and sets the TTL without materializing" "$(grep ALTER "$CALL_LOG")"
  fi
else
  fail "fresh table: attaches the policy and sets the TTL" "exited non-zero: $(cat "$WORK/err")"
fi
if grep -q "s3cret-pass" "$CALL_LOG"; then fail "password stays off argv" "found on argv"
else pass "password stays off argv"; fi
if grep -q "MATERIALIZE TTL" "$CALL_LOG"; then fail "materialize off: never materializes" "issued MATERIALIZE TTL"
else pass "materialize off: never materializes"; fi
if run MOCK_PENDING=5 && ! grep -q "system.parts" "$CALL_LOG"; then pass "materialize off: never reads parts"
else fail "materialize off: never reads parts" "$(cat "$CALL_LOG")"; fi

if run TTL_MATERIALIZE=true MOCK_PENDING=138 && materialized; then
  pass "materialize on, parts without the TTL: materializes on the cluster"
else
  fail "materialize on, parts without the TTL: materializes on the cluster" "$(cat "$WORK/out" "$WORK/err")"
fi
if run TTL_MATERIALIZE=true MOCK_PENDING=0 && ! materialized && grep -q "nothing to materialize" "$WORK/out"; then
  pass "materialize on, every part has the TTL: no-op (a re-sync)"
else
  fail "materialize on, every part has the TTL: no-op (a re-sync)" "$(cat "$WORK/out")"
fi
if run TTL_MATERIALIZE=true MOCK_PENDING=40 MOCK_RUNNING=1 && ! materialized && grep -q "still running" "$WORK/out"; then
  pass "materialize on, a run in flight: skips"
else
  fail "materialize on, a run in flight: skips" "$(cat "$WORK/out")"
fi
# Each failure case must exit non-zero and never reach MATERIALIZE.
fails_without_materialize() {  # name, run args...
  name="$1"; shift
  if run "$@"; then fail "$name" "exited 0"
  elif materialized; then fail "$name" "materialized anyway"
  else pass "$name"; fi
}
fails_without_materialize "unreadable part count fails the Job" TTL_MATERIALIZE=true MOCK_PENDING=garbage
fails_without_materialize "unreadable mutations fail the Job" TTL_MATERIALIZE=true MOCK_PENDING=3 MOCK_RUNNING=garbage
fails_without_materialize "a replica that does not match fails before materializing" TTL_MATERIALIZE=true MOCK_PENDING=3 MOCK_OK=1
# Each pre-flight failure case must exit non-zero and never reach clickhouse-client.
fails_before_any_query() {  # name, run args...
  name="$1"; shift
  if run "$@"; then fail "$name" "exited 0"
  elif [ -s "$CALL_LOG" ]; then fail "$name" "queried anyway"
  else pass "$name"; fi
}
fails_before_any_query "a bad TTL_MATERIALIZE fails before any query" TTL_MATERIALIZE=yes
fails_before_any_query "a bad TTL_TABLES entry fails before any query" TTL_TABLES=traces-local:13

if run MOCK_EXISTS=0; then
  if [ "$(alters)" = 0 ] && grep -q "skip opik.traces_local" "$WORK/out"; then
    pass "missing table: skipped, no ALTER"
  else
    fail "missing table: skipped, no ALTER" "$(cat "$WORK/out")"
  fi
else
  fail "missing table: skipped" "exited non-zero"
fi

if run MOCK_EXISTS=garbage; then fail "unreadable EXISTS fails the Job" "exited 0"
else pass "unreadable EXISTS fails the Job"; fi

if run MOCK_POLICY_REPLICAS=1 MOCK_POLICY=default; then
  fail "policy missing on a replica fails before any ALTER" "exited 0"
else
  if [ "$(alters)" = 0 ]; then pass "policy missing on a replica fails before any ALTER"
  else fail "policy missing on a replica fails before any ALTER" "$(alters) ALTERs"; fi
fi

if run MOCK_OK=1; then fail "a replica that does not match fails the Job" "exited 0"
else pass "a replica that does not match fails the Job"; fi

if run MOCK_POLICY=default MOCK_ALTER_FAIL=1; then fail "a failed ALTER fails the Job" "exited 0"
else pass "a failed ALTER fails the Job"; fi

if run ANALYTICS_DB_MIGRATIONS_PASS='' SRC0_ANALYTICS_DB_MIGRATIONS_PASS=cm-pass SRC1_ANALYTICS_DB_MIGRATIONS_PASS=secret-pass MOCK_EXPECT_PASS=secret-pass; then
  pass "a later source (the Secret) wins over the ConfigMap"
else
  fail "a later source (the Secret) wins over the ConfigMap" "$(cat "$WORK/err")"
fi
if run ANALYTICS_DB_MIGRATIONS_PASS='' SRC0_ANALYTICS_DB_MIGRATIONS_PASS=cm-pass SRC1_ANALYTICS_DB_MIGRATIONS_PASS='' MOCK_EXPECT_PASS=cm-pass; then
  pass "a source without the key falls back to the earlier one"
else
  fail "a source without the key falls back to the earlier one" "$(cat "$WORK/err")"
fi

fails_before_any_query "missing credential fails before any query" ANALYTICS_DB_MIGRATIONS_PASS=''

[ "$FAILED" -eq 0 ] || { echo "$FAILED failed"; exit 1; }
echo "all passed"
