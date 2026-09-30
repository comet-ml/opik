#!/bin/sh
# Executable coverage for the cold-tier migration Job's embedded shell script.
#
# helm-unittest asserts rendered manifests; it never runs the `/bin/sh -c` body,
# so the script's control flow (absence vs API error, retry budget, deadline-capped
# sleeps) is otherwise untested. This renders the chart, extracts that script, and
# runs its operator-bounce section against a mocked kubectl.
#
# Usage: tests/shell/cold_tier_migration_script_test.sh
# Requires: helm, python3. Run from the chart directory or anywhere.
set -eu

CHART_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

FAILED=0
pass() { echo "  ok   - $1"; }
fail() { echo "  FAIL - $1"; echo "         $2"; FAILED=$((FAILED + 1)); }

# --- render and extract -----------------------------------------------------
# The chart's subchart dependencies are irrelevant to this one template, so render
# from a copy with `dependencies` stripped rather than requiring a network fetch.
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
  --set clickhouse.tieredStorage.cold.s3.endpoint=https://s3.example/cold/ \
  -s templates/clickhouse-cold-tier-migration-job.yaml > "$WORK/rendered.yaml"

python3 - "$WORK/rendered.yaml" "$WORK/script.sh" <<'PY'
import sys, yaml
src, dst = sys.argv[1], sys.argv[2]
for doc in yaml.safe_load_all(open(src)):
    if doc and doc.get("kind") == "Job":
        open(dst, "w").write(doc["spec"]["template"]["spec"]["containers"][0]["command"][2])
        break
else:
    raise SystemExit("no Job in rendered output")
PY

# The script is one `set -eu` program; run only the operator-bounce section so the
# test does not need to mock the entire StatefulSet lifecycle.
python3 - "$WORK/script.sh" "$WORK/bounce.sh" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
t = open(src).read()
start = t.index('echo ">> restarting operator')
end = t.index("# Wait until every CH pod is Ready")
open(dst, "w").write(t[start:end])
PY

sh -n "$WORK/script.sh" || { echo "rendered script is not valid POSIX sh"; exit 1; }

# --- mocked kubectl ---------------------------------------------------------
mkdir -p "$WORK/bin"
cat > "$WORK/bin/kubectl" <<'MOCK'
#!/bin/sh
# Records every invocation as one line of argv in $CALL_LOG, and rejects any call
# that does not match the exact contract the script is supposed to use. A wrong
# namespace, a wrong Deployment, or a probe that forgot --ignore-not-found is a
# test failure rather than a silently accepted call.
printf '%s\n' "$*" >> "$CALL_LOG"

want_deploy="deployment/opik-altinity-clickhouse-operator"

case "$1" in
  get)
    # Expected: get deployment/<op> -n default -o name --ignore-not-found
    [ "$2" = "$want_deploy" ] || { echo "mock: probe targeted '$2', want '$want_deploy'" >&2; exit 64; }
    case " $* " in
      *" -n default "*) ;;
      *) echo "mock: probe missing '-n default': $*" >&2; exit 64 ;;
    esac
    case " $* " in
      *" --ignore-not-found"*) ;;
      *) echo "mock: probe missing --ignore-not-found: $*" >&2; exit 64 ;;
    esac
    case "${MOCK_MODE:-ok}" in
      absent)    exit 0 ;;                                                   # --ignore-not-found: empty, rc 0
      forbidden) echo 'Error from server (Forbidden): deployments.apps is forbidden' >&2; exit 1 ;;
      timeout)   echo 'Unable to connect to the server: dial tcp i/o timeout' >&2; exit 1 ;;
      wrapped)   echo 'error: unrelated failure mentioning not found in its text' >&2; exit 1 ;;
      *)         echo 'deployment.apps/opik-altinity-clickhouse-operator' ;;
    esac ;;
  rollout)
    # Expected: rollout restart deployment/<op> -n default
    [ "$2" = restart ] || { echo "mock: unexpected rollout subcommand '$2'" >&2; exit 64; }
    [ "$3" = "$want_deploy" ] || { echo "mock: restart targeted '$3', want '$want_deploy'" >&2; exit 64; }
    case " $* " in
      *" -n default "*) ;;
      *) echo "mock: restart missing '-n default': $*" >&2; exit 64 ;;
    esac
    [ "${MOCK_MODE:-ok}" = refused ] && { echo 'Error from server (Forbidden)' >&2; exit 1; }
    echo 'deployment.apps/opik-altinity-clickhouse-operator restarted' ;;
  *)
    echo "mock: unexpected kubectl verb '$1'" >&2; exit 64 ;;
esac
exit 0
MOCK
chmod +x "$WORK/bin/kubectl"

# Run the bounce section with a short budget and the helpers the full script defines
# above the extracted region.
run_bounce() { # $1=MOCK_MODE  $2=seconds of budget
  CALL_LOG="$WORK/calls"; export CALL_LOG
  : > "$CALL_LOG"
  (
    PATH="$WORK/bin:$PATH"
    export PATH
    MOCK_MODE="$1"; export MOCK_MODE
    OPERATOR_DEPLOY=opik-altinity-clickhouse-operator; export OPERATOR_DEPLOY
    NAMESPACE=default; export NAMESPACE
    POLL_TIMEOUT="$2"; export POLL_TIMEOUT
    DEADLINE=$(( $(date +%s) + $2 ))
    remaining() { r=$(( DEADLINE - $(date +%s) )); [ "$r" -gt 0 ] && echo "$r" || echo 0; }
    nap() { n="$1"; r="$(remaining)"; [ "$r" -lt "$n" ] && n="$r"; [ "$n" -gt 0 ] && sleep "$n"; return 0; }
    die() { echo "ERROR: $*" >&2; exit 1; }
    . "$WORK/bounce.sh"
  ) >"$WORK/out" 2>"$WORK/err"
}

echo "cold-tier migration script"

# A Deployment that genuinely does not exist means nothing was attempted: warn and
# succeed so the release is not failed for a cluster that self-heals passively.
if run_bounce absent 5; then
  grep -q "not found" "$WORK/err" \
    && pass "confirmed absence exits 0 with a warning" \
    || fail "confirmed absence exits 0 with a warning" "no warning on stderr"
else
  fail "confirmed absence exits 0 with a warning" "exited non-zero"
fi

# A Forbidden says nothing about whether the operator exists. Skipping the bounce
# here would report a successful migration on an unrecovered cluster.
if run_bounce forbidden 2; then
  fail "Forbidden probe fails the hook" "exited 0"
else
  grep -q "could not determine whether" "$WORK/err" \
    && pass "Forbidden probe retries then fails the hook" \
    || fail "Forbidden probe retries then fails the hook" "wrong diagnostic"
fi

# Same for a connection error.
if run_bounce timeout 2; then
  fail "timeout probe fails the hook" "exited 0"
else
  pass "timeout probe retries then fails the hook"
fi

# Regression: an unrelated error whose text contains "not found" must NOT be read
# as a confirmed absence. This is why the probe uses --ignore-not-found rather
# than matching on kubectl's error text.
if run_bounce wrapped 2; then
  fail "unrelated 'not found' text is not treated as absence" "exited 0 - matched on error text"
else
  pass "unrelated 'not found' text is not treated as absence"
fi

# The operator exists but the restart is rejected: the bounce was attempted and
# refused, so this must not be reported as a successful migration.
if run_bounce refused 5; then
  fail "refused restart fails the hook" "exited 0"
else
  grep -q "restart was refused" "$WORK/err" \
    && pass "refused restart fails the hook" \
    || fail "refused restart fails the hook" "wrong diagnostic"
fi

# Healthy path: probe succeeds, restart succeeds, control reaches the readiness wait.
if run_bounce ok 5; then
  grep -q "restarting operator" "$WORK/err" || grep -q "restarting operator" "$WORK/out" \
    && pass "healthy path restarts the operator and continues" \
    || fail "healthy path restarts the operator and continues" "no restart message"
else
  fail "healthy path restarts the operator and continues" "exited non-zero"
fi

# activeDeadlineSeconds need only exceed pollTimeoutSeconds by 1s, so an
# unconditional `sleep 10` could let the kubelet kill the Job before the script
# prints its own diagnosis. nap() must never sleep past the deadline.
elapsed=$( (
  DEADLINE=$(( $(date +%s) + 2 ))
  remaining() { r=$(( DEADLINE - $(date +%s) )); [ "$r" -gt 0 ] && echo "$r" || echo 0; }
  nap() { n="$1"; r="$(remaining)"; [ "$r" -lt "$n" ] && n="$r"; [ "$n" -gt 0 ] && sleep "$n"; return 0; }
  s=$(date +%s); nap 10; e=$(date +%s); echo $(( e - s ))
) )
[ "$elapsed" -le 3 ] \
  && pass "nap() caps the sleep at the remaining budget (${elapsed}s, not 10s)" \
  || fail "nap() caps the sleep at the remaining budget" "slept ${elapsed}s"

# Every retry site must use nap(); a bare `sleep` can overshoot the deadline.
if grep -qE '^\s+sleep [0-9]+' "$WORK/script.sh"; then
  fail "all retry sleeps are deadline-capped" "found a bare 'sleep' outside nap()"
else
  pass "all retry sleeps are deadline-capped"
fi

# A confirmed absence must not go on to restart anything: the whole point of the
# early exit is that there is no operator here to bounce.
run_bounce absent 5 || true
if grep -q '^rollout' "$WORK/calls"; then
  fail "confirmed absence does not attempt a restart" "rollout was called after an absent probe"
else
  pass "confirmed absence does not attempt a restart"
fi

# The healthy path must probe first, then restart exactly once, in that order.
run_bounce ok 5 || true
seq=$(cut -d' ' -f1 "$WORK/calls" | tr '\n' ',')
[ "$seq" = "get,rollout," ] \
  && pass "healthy path calls get then rollout, once each" \
  || fail "healthy path calls get then rollout, once each" "call sequence was '$seq'"

# The mock rejects a malformed call with rc 64; prove that guard is live, so a
# future change that drops --ignore-not-found or targets the wrong object fails
# here instead of passing silently.
CALL_LOG="$WORK/probe-guard"; export CALL_LOG; : > "$CALL_LOG"
if PATH="$WORK/bin:$PATH" MOCK_MODE=ok kubectl get deployment/wrong-name -n default -o name --ignore-not-found >/dev/null 2>&1; then
  fail "mock rejects a probe against the wrong Deployment" "call was accepted"
else
  pass "mock rejects a probe against the wrong Deployment"
fi
CALL_LOG="$WORK/probe-guard2"; export CALL_LOG; : > "$CALL_LOG"
if PATH="$WORK/bin:$PATH" MOCK_MODE=ok kubectl get deployment/opik-altinity-clickhouse-operator -n default -o name >/dev/null 2>&1; then
  fail "mock rejects a probe without --ignore-not-found" "call was accepted"
else
  pass "mock rejects a probe without --ignore-not-found"
fi

echo
if [ "$FAILED" -gt 0 ]; then
  echo "FAILED: $FAILED assertion(s)"
  exit 1
fi
echo "all assertions passed"
