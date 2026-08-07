#!/usr/bin/env bash
# pre-commit hook entry: run the PMD inline-FQN rule only on the Java files
# pre-commit passes changed files, never the whole module.
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
#
# Cache validity is decided by content, not by the directory existing: a stale or
# aborted run can leave an empty (or partial) lib_dir behind, and Java would then
# fail with ClassNotFoundException on PmdCli instead of the cache being repaired.
# cache_ready checks for the two jars we actually launch from, so an invalid
# directory is re-resolved rather than trusted.
#
# Publication is a single atomic rename out of a process-private staging dir, so
# a partially-populated cache is never visible under lib_dir; a concurrent writer
# either wins the rename or discards its staging copy.
cache_ready() {
	[ -d "$1" ] &&
		compgen -G "$1/pmd-cli-*.jar" >/dev/null &&
		compgen -G "$1/pmd-java-*.jar" >/dev/null
}

if ! cache_ready "$lib_dir"; then
	# Drop an invalid cache so the rename below can publish a good one.
	rm -rf "$lib_dir"
	staging="${lib_dir}.staging.$$"
	rm -rf "$staging"
	if mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy-dependencies \
		-f "$(dirname "$0")/pmd-cli-pom.xml" \
		-DoutputDirectory="$staging" \
		-Dpmd.version="$PMD_VERSION" >/dev/null &&
		cache_ready "$staging"; then
		# mv onto an existing dir would nest instead of replace, so only the
		# first writer publishes; a concurrent winner is equally valid.
		mv -n "$staging" "$lib_dir" 2>/dev/null || true
	fi
	rm -rf "$staging"
	if ! cache_ready "$lib_dir"; then
		echo "precommit-pmd: failed to resolve PMD ${PMD_VERSION} from Maven Central." >&2
		exit 1
	fi
fi

file_list="${TMPDIR:-/tmp}/opik-pmd-files.$$"
printf '%s\n' "$@" >"$file_list"
trap 'rm -f "$file_list"' EXIT

# PMD accepts a bare `// NOPMD` or a bare @SuppressWarnings with no rationale,
# which would silently defeat the "suppress with a reason" requirement the rule
# message, CONTRIBUTING.md and the backend skill all state. PMD has no option to
# demand one, so enforce it here before invoking the CLI.
if ! python3 "$(dirname "$0")/precommit-pmd-suppressions.py" "$@"; then
	echo "precommit-pmd: suppressions of InlineFullyQualifiedName must state a reason." >&2
	exit 1
fi

exec java -cp "$lib_dir/*" net.sourceforge.pmd.cli.PmdCli \
	check --rulesets "$RULESET" --file-list "$file_list" \
	--format text --no-progress --no-cache
