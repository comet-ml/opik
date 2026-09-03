#!/usr/bin/env bash
set -euo pipefail

NUM_GROUPS=16
UNIT_TIMEOUT=10
INTEGRATION_TIMEOUT=20
# Per-test timeout handed to JUnit, overriding the default in junit-platform.properties.
# CI reruns failures 3 times, so a deterministic hang costs 4x these values: 8m against the 10m
# unit wall, 16m against the 20m integration wall. Both must stay under their job timeout above,
# or the job is cancelled before Surefire can name the offending test.
UNIT_TEST_TIMEOUT=2m
INTEGRATION_TEST_TIMEOUT=4m
TEST_DIR="src/test/java"
PATTERN="DropwizardAppExtensionProvider\|MySQLContainer\|ClickHouseContainer\|RedisContainer\|MinIOContainer"

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

# Balanced split: sort integration files by line count desc,
# then greedy-assign each to the smallest group
declare -a group_size group_list
for ((i=1; i<=NUM_GROUPS; i++)); do
  group_size[$i]=0
  group_list[$i]=""
done

while IFS=' ' read -r lines file; do
  min_g=1
  for ((i=2; i<=NUM_GROUPS; i++)); do
    if (( group_size[i] < group_size[min_g] )); then
      min_g=$i
    fi
  done
  group_size[$min_g]=$(( group_size[min_g] + lines ))
  c=$(to_class "$file")
  group_list[$min_g]="${group_list[$min_g]:+${group_list[$min_g]},}$c"
done < <(
  for f in "${integration_files[@]}"; do
    echo "$(wc -l < "$f") $f"
  done | sort -rn
)

echo "Balanced ${#integration_files[@]} integration classes into $NUM_GROUPS groups"
for ((i=1; i<=NUM_GROUPS; i++)); do
  count=$(echo "${group_list[$i]}" | tr ',' '\n' | grep -c '.' || true)
  echo "  Group $i: $count classes (~${group_size[$i]} lines)"
done

# Build JSON matrix: unit tests + N integration groups
matrix="{\"include\":["
matrix+="{\"name\":\"Unit Tests\",\"tests\":\"$unit_list\",\"timeout\":$UNIT_TIMEOUT,\"testTimeout\":\"$UNIT_TEST_TIMEOUT\"}"
for ((i=1; i<=NUM_GROUPS; i++)); do
  matrix+=",{\"name\":\"Integration Group $i\",\"tests\":\"${group_list[$i]}\",\"timeout\":$INTEGRATION_TIMEOUT,\"testTimeout\":\"$INTEGRATION_TEST_TIMEOUT\"}"
done
matrix+="]}"
echo "matrix=$matrix" >> "$GITHUB_OUTPUT"
echo "Matrix written to GITHUB_OUTPUT ($(( NUM_GROUPS + 1 )) jobs: 1 unit + $NUM_GROUPS integration)"

# Summary
echo "### Test Split Summary" >> "$GITHUB_STEP_SUMMARY"
echo "- **Unit tests**: ${#unit_files[@]} classes" >> "$GITHUB_STEP_SUMMARY"
echo "- **Integration tests**: ${#integration_files[@]} classes in $NUM_GROUPS groups" >> "$GITHUB_STEP_SUMMARY"
for ((i=1; i<=NUM_GROUPS; i++)); do
  count=$(echo "${group_list[$i]}" | tr ',' '\n' | grep -c '.' || true)
  echo "  - Group $i: $count classes (~${group_size[$i]} lines)" >> "$GITHUB_STEP_SUMMARY"
done
