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
# Resolve into a process-private staging dir and publish it with a single atomic
# rename, so a partially-populated cache is never visible under lib_dir. A
# non-empty check alone can't distinguish a complete cache from one left behind
# by an interrupted or concurrent resolve, and PmdCli would then run against a
# broken classpath. The rename loser just discards its staging copy.
if [ ! -d "$lib_dir" ]; then
	staging="${lib_dir}.staging.$$"
	rm -rf "$staging"
	if mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy-dependencies \
		-f "$(dirname "$0")/pmd-cli-pom.xml" \
		-DoutputDirectory="$staging" \
		-Dpmd.version="$PMD_VERSION" >/dev/null; then
		# mv onto an existing dir would nest instead of replace, so only the
		# first writer publishes; a concurrent winner is equally valid.
		mv -n "$staging" "$lib_dir" 2>/dev/null || true
	fi
	rm -rf "$staging"
	if [ ! -d "$lib_dir" ]; then
		echo "precommit-pmd: failed to resolve PMD ${PMD_VERSION} from Maven Central." >&2
		exit 1
	fi
fi

file_list="${TMPDIR:-/tmp}/opik-pmd-files.$$"
printf '%s\n' "$@" >"$file_list"
trap 'rm -f "$file_list"' EXIT

# PMD accepts a bare `// NOPMD` or a bare @SuppressWarnings with no rationale,
# which would silently defeat the "suppress with a reason" requirement the rule
# message and CONTRIBUTING.md both state. PMD has no option to demand one, so
# enforce it here: a suppression of this rule must carry trailing prose.
#
# `// NOPMD` on its own line or with nothing after it (optionally after a `-` or
# `:` separator) is rejected; `// NOPMD - collides with java.util.Date` passes.
bare=0
while IFS= read -r f; do
	[ -f "$f" ] || continue
	if grep -nE '//[[:space:]]*NOPMD[[:space:]]*([-:][[:space:]]*)?$' "$f" >/dev/null; then
		grep -nE '//[[:space:]]*NOPMD[[:space:]]*([-:][[:space:]]*)?$' "$f" \
			| sed "s|^|$f:|;s|$| <- bare // NOPMD: add a reason, e.g. '// NOPMD - collides with the imported java.util.Date'|" >&2
		bare=1
	fi
	# A bare @SuppressWarnings for this rule needs a reason too — as a trailing
	# comment on the annotation line, or a comment on the line above it.
	while IFS=: read -r ln _; do
		[ -n "$ln" ] || continue
		this=$(sed -n "${ln}p" "$f")
		prev=$(sed -n "$((ln - 1))p" "$f")
		case "$this" in *'//'*) continue ;; esac
		case "$prev" in *'//'* | *'*'*) continue ;; esac
		echo "$f:$ln: bare @SuppressWarnings(\"PMD.InlineFullyQualifiedName\"): add a reason as a comment on this line or the line above" >&2
		bare=1
	done <<EOF
$(grep -nE '@SuppressWarnings\(.*PMD\.InlineFullyQualifiedName' "$f" || true)
EOF
done <"$file_list"

if [ "$bare" -ne 0 ]; then
	echo "precommit-pmd: suppressions of InlineFullyQualifiedName must state a reason." >&2
	exit 1
fi

exec java -cp "$lib_dir/*" net.sourceforge.pmd.cli.PmdCli \
	check --rulesets "$RULESET" --file-list "$file_list" \
	--format text --no-progress --no-cache
