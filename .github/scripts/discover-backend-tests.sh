#!/usr/bin/env bash
set -euo pipefail

NUM_GROUPS=${NUM_GROUPS:-24}
UNIT_TIMEOUT=10
INTEGRATION_TIMEOUT=20
# Per-test timeout handed to JUnit, overriding the default in junit-platform.properties.
# CI reruns failures 3 times, so a deterministic hang costs 4x these values: 8m against the 10m
# unit wall, 16m against the 20m integration wall. Both must stay under their job timeout above,
# or the job is cancelled before Surefire can name the offending test.
UNIT_TEST_TIMEOUT=2m
INTEGRATION_TEST_TIMEOUT=4m
# Surefire JVMs per job. Unit classes share nothing outside their JVM, so they spread over the runner's
# 4 vCPUs. Integration classes stay in one JVM so they keep reusing its containers.
UNIT_FORK_COUNT=4
INTEGRATION_FORK_COUNT=1
TEST_DIR="src/test/java"
PATTERN="DropwizardAppExtensionProvider\|MySQLContainer\|ClickHouseContainer\|RedisContainer\|MinIOContainer"
# Measured seconds per integration class and per @Nested class (class<TAB>seconds), refreshed by
# update-backend-test-timings.sh from a run's Surefire reports. Relative to apps/opik-backend.
TIMINGS_FILE=${TIMINGS_FILE:-../../.github/scripts/backend-test-timings.tsv}
# A class measured above this many seconds is split across groups by its @Nested classes, so the
# slowest class no longer sets the floor of the slowest group. Each extra part pays the class setup again.
SPLIT_ABOVE_SECONDS=${SPLIT_ABOVE_SECONDS:-200}
PART_SETUP_SECONDS=15
GITHUB_OUTPUT=${GITHUB_OUTPUT:-/dev/null}
GITHUB_STEP_SUMMARY=${GITHUB_STEP_SUMMARY:-/dev/null}

# Classify: integration tests reference container/Dropwizard patterns
integration_files=()
unit_files=()
while IFS= read -r file; do
  if grep -ql "$PATTERN" "$file" 2>/dev/null; then
    integration_files+=("$file")
  else
    unit_files+=("$file")
  fi
done < <(find "$TEST_DIR" -name "*Test.java" -o -name "*Tests.java" | sort)

echo "Classified ${#unit_files[@]} unit test classes and ${#integration_files[@]} integration test classes"

# Convert file path to Maven class pattern: com.foo.BarTest
to_class() { echo "$1" | sed "s|^$TEST_DIR/||;s|/|.|g;s|\.java$||"; }

# Build unit test list
unit_list=""
for f in "${unit_files[@]}"; do
  c=$(to_class "$f")
  unit_list="${unit_list:+$unit_list,}$c"
done

if [[ ! -f "$TIMINGS_FILE" ]]; then
  echo "No timings file at $TIMINGS_FILE: estimating every class from its size"
  TIMINGS_FILE=/dev/null
fi

# One line per integration class: class, line count and its direct @Nested classes. The formatter
# (spotless) puts a top-level class's members at 4 spaces, so a direct @Nested child is the first
# class declared at that indent after an @Nested annotation.
classes=$(
  for f in "${integration_files[@]}"; do
    nested=$(awk '/^    @Nested/ { pending = 1; next }
      pending && match($0, /^    ([a-z]+ )*class [A-Za-z0-9_]+/) {
        n = split(substr($0, RSTART, RLENGTH), w, " "); printf "%s%s", sep, w[n]; sep = ","; pending = 0 }' "$f")
    printf '%s\t%s\t%s\n' "$(to_class "$f")" "$(wc -l < "$f" | tr -d ' ')" "$nested"
  done
)

# Balanced split, longest first: estimate every class, split those above SPLIT_ABOVE_SECONDS into parts
# by @Nested class (the first part also takes the class's own tests, "Class#*"), sort the parts and
# classes by estimate descending, then greedy-assign each to the group with the least time so far.
# A class without a measured time (new or renamed) is estimated from its line count at the median
# seconds per line of the measured classes. Prints "group<TAB>seconds<TAB>tests<TAB>measured|estimated".
assignments=$(CLASSES="$classes" python3 - "$TIMINGS_FILE" "$NUM_GROUPS" "$SPLIT_ABOVE_SECONDS" "$PART_SETUP_SECONDS" <<'PY'
import math
import os
import statistics
import sys

timings_file, groups, split_above, part_setup = sys.argv[1], int(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4])
seconds = {}
with open(timings_file) as f:
    for line in f:
        if line.strip() and not line.startswith("#"):
            name, value = line.rstrip("\n").split("\t")
            seconds[name] = float(value)

classes = []
for line in os.environ["CLASSES"].splitlines():
    name, lines, nested = line.split("\t")
    classes.append((name, int(lines), [n for n in nested.split(",") if n]))
per_line = statistics.median([seconds[c] / max(n, 1) for c, n, _ in classes if c in seconds] or [0.1])

items = []  # (estimate, tests, source)
for name, lines, nested in classes:
    if name not in seconds:
        items.append((lines * per_line, name, "estimated"))
        continue
    total = seconds[name]
    if total <= split_above or len(nested) < 2:
        items.append((total, name, "measured"))
        continue
    # Nested classes without a measured time share what the class's measured parts leave unexplained
    known = {n: seconds[f"{name}${n}"] for n in nested if f"{name}${n}" in seconds}
    unknown = [n for n in nested if n not in known]
    rest = max(total - sum(known.values()), 0)
    own = rest if not unknown else rest / (len(unknown) + 1)
    times = {**known, **{n: own for n in unknown}}
    parts = min(math.ceil(total / split_above), len(nested))
    bins = [[part_setup, [f"{name}#*"]]] + [[part_setup, []] for _ in range(parts - 1)]
    bins[0][0] += own
    for n in sorted(nested, key=lambda n: (-times[n], n)):
        target = min(bins, key=lambda b: b[0])
        target[0] += times[n]
        target[1].append(f"{name}${n}")
    items += [(load, ",".join(tests), "measured") for load, tests in bins if tests]

items.sort(key=lambda item: (-item[0], item[1]))
load = [0.0] * groups
for estimate, tests, source in items:
    g = load.index(min(load))
    load[g] += estimate
    print(f"{g + 1}\t{estimate:.0f}\t{tests}\t{source}")
PY
)

declare -a group_list group_seconds group_count
for ((i=1; i<=NUM_GROUPS; i++)); do
  group_list[$i]=""
  group_seconds[$i]=0
  group_count[$i]=0
done
estimated=0
split_parts=0
while IFS=$'\t' read -r g seconds tests src; do
  group_list[$g]="${group_list[$g]:+${group_list[$g]},}$tests"
  group_seconds[$g]=$(( group_seconds[g] + seconds ))
  group_count[$g]=$(( group_count[g] + 1 ))
  if [[ $src == estimated ]]; then
    estimated=$(( estimated + 1 ))
  fi
  if [[ $tests == *'$'* ]]; then
    split_parts=$(( split_parts + 1 ))
  fi
done <<< "$assignments"

echo "Balanced ${#integration_files[@]} integration classes into $NUM_GROUPS groups by run time ($estimated estimated from size, $split_parts parts of split classes)"
for ((i=1; i<=NUM_GROUPS; i++)); do
  echo "  Group $i: ${group_count[$i]} classes or parts (~${group_seconds[$i]}s)"
done

# Build JSON matrix: unit tests + N integration groups
matrix="{\"include\":["
matrix+="{\"name\":\"Unit Tests\",\"id\":\"unit\",\"tests\":\"$unit_list\",\"timeout\":$UNIT_TIMEOUT,\"testTimeout\":\"$UNIT_TEST_TIMEOUT\",\"forkCount\":$UNIT_FORK_COUNT,\"pullImages\":false}"
for ((i=1; i<=NUM_GROUPS; i++)); do
  matrix+=",{\"name\":\"Integration Group $i\",\"id\":\"integration-$i\",\"tests\":\"${group_list[$i]}\",\"timeout\":$INTEGRATION_TIMEOUT,\"testTimeout\":\"$INTEGRATION_TEST_TIMEOUT\",\"forkCount\":$INTEGRATION_FORK_COUNT,\"pullImages\":true}"
done
matrix+="]}"
echo "matrix=$matrix" >> "$GITHUB_OUTPUT"
echo "Matrix written to GITHUB_OUTPUT ($(( NUM_GROUPS + 1 )) jobs: 1 unit + $NUM_GROUPS integration)"

# Summary
echo "### Test Split Summary" >> "$GITHUB_STEP_SUMMARY"
echo "- **Unit tests**: ${#unit_files[@]} classes" >> "$GITHUB_STEP_SUMMARY"
echo "- **Integration tests**: ${#integration_files[@]} classes in $NUM_GROUPS groups by measured run time ($estimated estimated from size, $split_parts parts of split classes)" >> "$GITHUB_STEP_SUMMARY"
for ((i=1; i<=NUM_GROUPS; i++)); do
  echo "  - Group $i: ${group_count[$i]} classes or parts (~${group_seconds[$i]}s)" >> "$GITHUB_STEP_SUMMARY"
done
