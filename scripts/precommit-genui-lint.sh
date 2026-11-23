#!/usr/bin/env bash
set -euo pipefail

[ "$#" -eq 0 ] && exit 0

prefix="sdks/genui"
files=()
for f in "$@"; do
	case "$f" in
	"$prefix"/*.ts | "$prefix"/*.tsx | "$prefix"/*.js | "$prefix"/*.jsx | "$prefix"/*.mjs | "$prefix"/*.cjs | "$prefix"/*.css) files+=("${f#"$prefix"/}") ;;
	esac
done
[ "${#files[@]}" -eq 0 ] && exit 0

cd "$prefix"
status=0
lint_files=()
for f in "${files[@]}"; do
	case "$f" in
	*.css) ;;
	*) lint_files+=("$f") ;;
	esac
done
if [ "${#lint_files[@]}" -gt 0 ]; then
	npx oxlint --fix --max-warnings=0 "${lint_files[@]}" || status=1
fi
npx oxfmt "${files[@]}" || status=1
exit "$status"
