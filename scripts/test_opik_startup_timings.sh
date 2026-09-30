#!/usr/bin/env bash
# Tests for opik.sh's container startup wait: the retry budget and the timing table.
#
# These drive the REAL start_missing_containers. opik.sh is sourced with
# OPIK_SOURCE_ONLY=1 so it defines its functions and stops before parsing arguments,
# then the few things that would reach outside the wait loop are stubbed: docker, the
# compose command, the install report, and the docker/buildx preflight. Everything under
# test — the retry loop, record_timing, record_healthy_containers and the printed table —
# is the shipped implementation, so a production change that breaks the contract turns
# these red instead of leaving a parallel copy green.
#
# The stubbed docker scripts each container's transitions, so the timeout path runs in
# about a second with no daemon and no real waiting.
#
# Run from the repo root:  scripts/test_opik_startup_timings.sh
set -uo pipefail

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

stub_dir=$(mktemp -d)
state_dir=$(mktemp -d)
trap 'rm -rf "$stub_dir" "$state_dir"' EXIT

# ---------------------------------------------------------------------------
# Stubbed docker. Only `docker inspect -f <fmt> <name>` matters to the wait loop.
#
# $HEALTH_PLAN holds one entry per container: "name:s1,s2,s3" — the state it reports on
# successive iterations, with the last repeating forever. A state is a health value
# (starting/healthy/unhealthy) or "exited", which reports a non-running Status and empty
# Health, as the real CLI does for a dead container.
#
# The loop probes Status then Health each iteration; record_healthy_containers probes
# Health alone. Only the Status probe advances the plan, so one plan step is exactly one
# loop iteration and a bare Health probe observes the current state without consuming it.
# That is what lets a fixture express "healthy when the scan looks, gone when the loop
# arrives" — the interleaving the race test needs.
#
# Note start_missing_containers runs a pre-check pass over every container before the wait
# loop starts, and that pass does one Status probe each. So step 0 of every plan is
# consumed by the pre-check, and the wait loop sees the plan from step 1 onward.
# ---------------------------------------------------------------------------
cat >"$stub_dir/docker" <<'STUB'
#!/usr/bin/env bash
[ "${1:-}" = "inspect" ] || exit 0
fmt="$3"; name="$4"
plan=""
for entry in $HEALTH_PLAN; do
	case "$entry" in "$name":*) plan="${entry#*:}" ;; esac
done
[ -n "$plan" ] || exit 1   # unknown container: absent, as the real CLI would be
counter="$STATE_DIR/$name"
n=$(cat "$counter" 2>/dev/null || echo 0)
IFS=',' read -r -a steps <<<"$plan"
# Status and Health must describe the SAME step, or a container can appear running with an
# empty health (the unhealthy branch) when the fixture meant "exited". So resolve the value
# first, then let the Status probe advance the plan for the next iteration.
idx=$n
case "$fmt" in *State.Status*) echo $((n + 1)) >"$counter" ;; esac
[ "$idx" -ge "${#steps[@]}" ] && idx=$(( ${#steps[@]} - 1 ))
value="${steps[$idx]}"
# "healthy!" is the race: healthy to a Health-only probe (what the pre-sleep scan sees),
# already exited to a Status probe (what the loop sees when it finally arrives).
case "$fmt" in
	*State.Status*)
		case "$value" in
			exited|"healthy!") echo "exited" ;;
			*) echo "running" ;;
		esac ;;
	*State.Health.Status*)
		case "$value" in
			exited) echo "" ;;
			"healthy!") echo "healthy" ;;
			*) echo "$value" ;;
		esac ;;
esac
STUB
chmod +x "$stub_dir/docker"
export PATH="$stub_dir:$PATH" STATE_DIR="$state_dir"

# Load opik.sh's functions without running its CLI dispatch.
export OPIK_SOURCE_ONLY=1
# shellcheck disable=SC1091
source ./opik.sh
unset OPIK_SOURCE_ONLY

# Defaults normally set by the argument parser we skipped.
DEBUG_MODE=false
BUILD_MODE=
PROFILE_COUNT=0

# Neutralise only what reaches outside the wait loop.
check_docker_status() { :; }
send_install_report() { :; }
setup_buildx_bake() { :; }
create_opik_config_if_missing() { :; }
get_docker_compose_cmd() { echo true; }   # `$cmd up -d` becomes a no-op `true up -d`
# Don't actually wait, but do advance the clock the way a real sleep would — the loop
# derives every printed duration from SECONDS, so a no-op sleep would make the whole table
# read 0s and the attribution assertions would be vacuous.
sleep() { SECONDS=$((SECONDS + ${1:-1})); }

run_start() { # run_start <max_retries> <container>...
	local retries="$1"; shift
	rm -f "$state_dir"/*
	CONTAINERS=("$@")
	# SECONDS is a live counter; unset it so it becomes a plain variable and the loop's
	# own arithmetic is the only thing advancing it.
	unset SECONDS
	SECONDS=0
	# Let a caller-supplied value through untouched, so the validation tests can pass junk.
	OPIK_MAX_STARTUP_RETRIES="${OPIK_MAX_STARTUP_RETRIES-$retries}" start_missing_containers 2>&1
	echo "all_running=$all_running"
}

run_start_default() { # run_start_default <container>...  — no override; exercises the shipped default
	rm -f "$state_dir"/*
	CONTAINERS=("$@")
	unset SECONDS
	SECONDS=0
	unset OPIK_MAX_STARTUP_RETRIES
	start_missing_containers 2>&1
	echo "all_running=$all_running"
}

echo "retry budget:"
# The `be` container never becomes healthy: must time out at the configured budget and say so.
export HEALTH_PLAN="be:starting"
out=$(run_start 90 be)
check "times out at the configured budget" "still not healthy after 90s" "$out"
check "table carries the terminal value"   "TIMED OUT after 90s"         "$out"
check "run is marked failed"               "all_running=false"           "$out"
# Read from the real code path, so a changed budget is visible here rather than assumed.
out=$(run_start 5 be)
check "budget is the retry count, not a hardcoded 90" "still not healthy after 5s" "$out"

echo "override validation:"
# The override is test-only, but it still must not turn a typo into an instant timeout:
# unvalidated, "abc"/"0"/"-5" all make the first [[ retries -ge max_retries ]] true.
# Keep the container `starting` so the retry budget is actually reached. With a healthy
# container the loop exits before max_retries is ever compared, and these would pass even
# if the fallback value were wrong.
export HEALTH_PLAN="be:starting"
for bad in abc 0 -5 " " 12x; do
	out=$(OPIK_MAX_STARTUP_RETRIES="$bad" run_start 90 be)
	check "rejects '$bad'" "Ignoring OPIK_MAX_STARTUP_RETRIES" "$out"
	check "'$bad' falls back to the 90-retry budget" "still not healthy after 90s" "$out"
done
out=$(OPIK_MAX_STARTUP_RETRIES=7 run_start 90 be)
check_absent "a valid override is accepted silently" "Ignoring OPIK_MAX_STARTUP_RETRIES" "$out"
check "a valid override is actually applied" "still not healthy after 7s" "$out"

# The warning must not echo the value back — it would put attacker-controlled text on CI
# stdout, where ::workflow:: sequences or newlines can forge log annotations.
out=$(OPIK_MAX_STARTUP_RETRIES='x
::error::forged' run_start 90 be)
check_absent "the rejected value is not echoed into the log" "::error::forged" "$out"
check "the warning still names the variable" "Ignoring OPIK_MAX_STARTUP_RETRIES" "$out"

# Assert the shipped DEFAULT, with no override in play — otherwise every test here pins its
# own budget and a change to the default itself would go unnoticed.
export HEALTH_PLAN="be:starting"
out=$(OPIK_MAX_STARTUP_RETRIES= run_start_default be)
check "default budget is 90 retries" "still not healthy after 90s" "$out"

echo "per-container attribution (the regression the table exists to catch):"
# The case the pre-sleep scan exists for: gr sits AFTER the slow be and is NOT healthy at
# the start — it becomes healthy on step 1, while the loop is still blocked on be. Only the
# scan can observe that moment; without it, gr is first probed when the loop arrives at
# step 4 and is credited with be's wait instead of its own. Note gr must not be healthy at
# step 0, or the loop's own probe would record the right answer by accident and the test
# would pass even with the scan removed.
export HEALTH_PLAN="infra:healthy be:starting,starting,starting,starting,healthy gr:starting,healthy"
out=$(run_start 90 infra be gr)
check "slow container carries its own wait"     "be                         2s" "$out"
check_absent "later container does not echo the slow one's wait" "gr                         2s" "$out"
check "later container is credited when it actually went healthy" "gr                         0s" "$out"
check "run succeeded"                           "all_running=true"  "$out"

echo "healthy-then-exited race:"
# `gr` reports running+healthy while the loop is still on `be`, so the pre-sleep scan banks a
# duration for it. `gr` then exits, and the loop's Status probe finds it dead on arrival.
# The table must show the failure rather than the banked duration.
export HEALTH_PLAN="be:starting,starting,starting,starting,healthy gr:healthy!"
out=$(run_start 90 be gr)
check "the dead container is reported"          "failed to start" "$out"
check "terminal value replaces the banked one"  "gr                         failed to start" "$out"
check_absent "banked duration does not survive" "gr                         0s" "$out"
check "run is marked failed"                    "all_running=false" "$out"

echo "unhealthy state:"
export HEALTH_PLAN="be:unhealthy"
out=$(run_start 90 be)
check "unhealthy is surfaced, not silently timed" "unhealthy"         "$out"
check "run is marked failed"                      "all_running=false" "$out"

echo "table shape:"
export HEALTH_PLAN="infra:healthy be:healthy"
out=$(run_start 90 infra be)
check "header names the anchor"     "Container startup times (since compose up returned)" "$out"
check "total wall clock is printed" "Total wall clock:" "$out"
check "run succeeded"               "all_running=true"  "$out"

echo ""
if [ "$fails" -eq 0 ]; then echo "All startup timing tests passed."; else echo "$fails test(s) FAILED."; exit 1; fi
