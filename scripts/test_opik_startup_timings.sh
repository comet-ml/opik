#!/usr/bin/env bash
# Tests for opik.sh's container startup wait: the retry budget and the timing table.
# Stubs `docker` on PATH so health transitions are scripted and deterministic — no daemon,
# no containers, no sleeping for 90 real seconds. Run from the repo root:
#   scripts/test_opik_startup_timings.sh
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
fails=0
check() { # check <name> <expected-substring> <actual>
	if printf '%s' "$3" | grep -qF -- "$2"; then
		echo "  ok: $1"
	else
		echo "  FAIL: $1"
		echo "    expected to contain: $2"
		echo "    actual: $3"
		fails=$((fails + 1))
	fi
}
check_absent() { # check_absent <name> <unexpected-substring> <actual>
	if printf '%s' "$3" | grep -qF -- "$2"; then
		echo "  FAIL: $1 (did not expect: $2)"
		echo "    actual: $3"
		fails=$((fails + 1))
	else
		echo "  ok: $1"
	fi
}

# ---------------------------------------------------------------------------
# record_timing — the write-once/overwrite contract the table depends on.
# Lifted by sourcing opik.sh's definitions rather than re-implementing them.
# ---------------------------------------------------------------------------
# shellcheck disable=SC1090
eval "$(sed -n '/^record_timing()/,/^}/p' opik.sh)"

echo "record_timing:"
timing_labels=()
timing_values=()
record_timing be 12s
record_timing be 99s
check "healthy time is write-once" "12s" "${timing_values[0]}"
record_timing be "unhealthy: exited" true
check "terminal state overwrites a banked time" "unhealthy: exited" "${timing_values[0]}"
record_timing fe 5s
check "distinct containers get their own row" "5s" "${timing_values[1]}"

# ---------------------------------------------------------------------------
# The wait loop, driven by a stubbed docker.
#
# The stub reads a per-container script from $HEALTH_PLAN: "name:a,b,c" means that
# container reports a, then b, then c on successive inspects. The last value repeats.
# ---------------------------------------------------------------------------
stub_dir=$(mktemp -d)
state_dir=$(mktemp -d)
trap 'rm -rf "$stub_dir" "$state_dir"' EXIT

cat >"$stub_dir/docker" <<'STUB'
#!/usr/bin/env bash
# Only `docker inspect -f <fmt> <container>` is used by the wait loop.
[ "${1:-}" = "inspect" ] || exit 0
fmt="$3"; name="$4"
plan=""
for entry in $HEALTH_PLAN; do
	case "$entry" in "$name":*) plan="${entry#*:}" ;; esac
done
[ -n "$plan" ] || exit 1   # unknown container: absent, as the real CLI would be
counter="$STATE_DIR/$name"
n=$(cat "$counter" 2>/dev/null || echo 0)
# The wait loop inspects Status then Health per iteration, and record_healthy_containers
# inspects Health alone. Advance only on Status so one plan step == one loop iteration;
# a bare Health probe reads the current step without consuming it.
case "$fmt" in *State.Status*) echo $((n + 1)) >"$counter" ;; esac
IFS=',' read -r -a steps <<<"$plan"
idx=$n
[ "$idx" -ge "${#steps[@]}" ] && idx=$(( ${#steps[@]} - 1 ))
value="${steps[$idx]}"
# healthy_then_exited models the race the timing table has to report correctly: the
# container is healthy when the pre-sleep scan probes Health, but has exited by the time
# the loop reaches it and probes Status.
case "$fmt" in
	*State.Status*)
		case "$value" in
			exited|healthy_then_exited) echo "exited" ;;
			*) echo "running" ;;
		esac ;;
	*State.Health.Status*)
		case "$value" in
			exited) echo "" ;;
			healthy_then_exited) echo "healthy" ;;
			*) echo "$value" ;;
		esac ;;
esac
STUB
chmod +x "$stub_dir/docker"
export PATH="$stub_dir:$PATH" STATE_DIR="$state_dir"

# Minimal harness: the wait loop and table lifted out of start_missing_containers, with
# the real record_timing/record_healthy_containers. sleep is stubbed to a no-op so a
# 90-retry timeout runs instantly; SECONDS still advances via a counter we control.
run_wait() { # run_wait <max_retries> <container>...
	local max_retries="$1"; shift
	local containers=("$@")
	rm -f "$state_dir"/*
	timing_labels=()
	timing_values=()
	local wait_started_at=0 all_running=true container retries status health
	sleep() { :; }   # no real waiting
	# SECONDS is a live bash counter; unset it so it becomes a plain variable and the fake
	# clock below is the only thing advancing it. Otherwise real elapsed time is added on
	# top of our increments and the wall-clock assertion drifts.
	unset SECONDS
	SECONDS=0

	# shellcheck disable=SC1090
	eval "$(sed -n '/^record_healthy_containers()/,/^}/p' opik.sh)"

	for container in "${containers[@]}"; do
		retries=0
		while true; do
			status=$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null)
			health=$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null)
			if [[ "$status" != "running" ]]; then
				echo "❌ $container failed to start (status: $status)"
				all_running=false
				record_timing "$container" "failed to start" true
				break
			fi
			if [[ "$health" == "healthy" ]]; then
				record_timing "$container" "$((SECONDS - wait_started_at))s"
				break
			elif [[ "$health" == "starting" ]]; then
				record_healthy_containers
				sleep 1
				SECONDS=$((SECONDS + 1))
				retries=$((retries + 1))
				if [[ $retries -ge $max_retries ]]; then
					echo "⚠️  $container is still not healthy after ${max_retries}s"
					all_running=false
					record_timing "$container" "TIMED OUT after ${max_retries}s" true
					break
				fi
			else
				echo "❌ $container health state is '$health'"
				all_running=false
				record_timing "$container" "unhealthy: $health" true
				break
			fi
		done
	done

	echo "⏱  Container startup times (since compose up returned):"
	local i
	for i in "${!timing_labels[@]}"; do
		printf '     %-26s %s\n' "${timing_labels[$i]}" "${timing_values[$i]}"
	done
	echo "   Total wall clock: $((SECONDS - wait_started_at))s"
	echo "all_running=$all_running"
	unset -f sleep
}

echo "timeout contract:"
# backend never goes healthy; with max_retries=90 it must time out on the 90th retry.
export HEALTH_PLAN="be:starting"
out=$(run_wait 90 be)
check "times out at the configured budget" "still not healthy after 90s" "$out"
check "table shows the terminal value"     "TIMED OUT after 90s"         "$out"
check "marks the run as failed"            "all_running=false"           "$out"
check "wall clock reflects 90 polls"       "Total wall clock: 90s"       "$out"

echo "per-container attribution (the regression this table exists to catch):"
# be is slow; gr is healthy from the start but sits AFTER be in the list. Before the
# pre-sleep scan, gr inherited be's wait; now it must carry its own near-zero time.
export HEALTH_PLAN="infra:healthy be:starting,starting,starting,starting,healthy gr:healthy"
out=$(run_wait 90 infra be gr)
check "fast container recorded at 0s"   "infra                      0s" "$out"
check "slow container carries its wait" "be                         3s" "$out"
check_absent "later container does not echo the slow one's time" "gr                         3s" "$out"
check "later container gets its own early time" "gr                         0s" "$out"

echo "terminal states:"
# gr is healthy while the loop waits on be, so the pre-sleep scan banks a duration for it.
# gr then exits, and by the time the loop reaches it the status is no longer running. The
# table must surface that failure instead of the reassuring banked duration — this is the
# regression fixed in 7c250033cc, where first-write-wins discarded the terminal value.
# be consumes 2 steps before going healthy; gr's plan is only advanced by those same
# Status probes, so it must stay healthy for 2 steps and then exit.
export HEALTH_PLAN="be:starting,starting,healthy gr:healthy_then_exited"
out=$(run_wait 90 be gr)
check "a container that dies after going healthy is reported" "failed to start" "$out"
check "the terminal value replaces the banked duration" "gr                         failed to start" "$out"
check "and the run is marked failed"                    "all_running=false"       "$out"

echo "an unhealthy container is not silently timed:"
export HEALTH_PLAN="be:unhealthy"
out=$(run_wait 90 be)
check "unhealthy state is surfaced" "unhealthy" "$out"
check "run marked failed"           "all_running=false" "$out"

echo ""
if [ "$fails" -eq 0 ]; then echo "All startup timing tests passed."; else echo "$fails test(s) FAILED."; exit 1; fi
