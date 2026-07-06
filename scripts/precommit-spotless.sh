#!/usr/bin/env bash
# pre-commit hook entry: run Spotless only on the Java files pre-commit passes in
# (changed files), never the whole module. `-DspotlessFiles` takes a regex matched
# against absolute file paths, so we build an alternation of the escaped paths.
set -euo pipefail

[ "$#" -eq 0 ] && exit 0

# Build a `-DspotlessFiles` regex (matched against absolute paths) from the changed
# files. Java source paths here only ever contain [A-Za-z0-9/_.-], so `.` is the only
# regex metacharacter that can appear — escape it so e.g. `.java` can't match `Xjava`.
regex=""
for f in "$@"; do
	esc=${f//./\\.}
	regex="${regex:+$regex|}.*${esc}"
done

exec mvn -q -f apps/opik-backend/pom.xml spotless:apply -DspotlessFiles="$regex"
