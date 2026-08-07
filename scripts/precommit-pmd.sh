#!/usr/bin/env bash
# pre-commit hook entry: run the PMD inline-FQN rule only on the Java files
# pre-commit passes in (changed files), never the whole module.
#
# PMD is invoked through its CLI rather than maven-pmd-plugin because the
# plugin's `includes` parameter is pom-only (no -D user property), so it cannot
# be scoped to a changed-file list — it would scan all of apps/opik-backend on
# every Java commit. The CLI takes an explicit --file-list.
#
# pmd/pmd publishes no pre-commit hook, so PMD is resolved from Maven Central
# into a cached lib dir (reusing the Maven toolchain the spotless hook already
# needs). PMD_VERSION is the upstream pin.
set -euo pipefail

[ "$#" -eq 0 ] && exit 0

PMD_VERSION="7.26.0"
RULESET="apps/opik-backend/pmd-ruleset.xml"
lib_dir="${TMPDIR:-/tmp}/opik-pmd-${PMD_VERSION}"

# Resolve pmd-cli + its transitive deps into lib_dir once; later runs reuse it.
if [ ! -d "$lib_dir" ] || [ -z "$(ls -A "$lib_dir" 2>/dev/null)" ]; then
	mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy-dependencies \
		-f "$(dirname "$0")/pmd-cli-pom.xml" \
		-DoutputDirectory="$lib_dir" \
		-Dpmd.version="$PMD_VERSION" >/dev/null
fi

file_list="${TMPDIR:-/tmp}/opik-pmd-files.$$"
printf '%s\n' "$@" >"$file_list"
trap 'rm -f "$file_list"' EXIT

exec java -cp "$lib_dir/*" net.sourceforge.pmd.cli.PmdCli \
	check --rulesets "$RULESET" --file-list "$file_list" \
	--format text --no-progress --no-cache
