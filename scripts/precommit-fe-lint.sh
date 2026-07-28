#!/usr/bin/env bash
# pre-commit hook entry: lint+fix only the frontend files pre-commit passes in
# (changed files), never the whole src tree. Routes by extension: JS/TS → eslint,
# styles → stylelint. Uses the repo's own node_modules (language: system).
set -euo pipefail

[ "$#" -eq 0 ] && exit 0

prefix="apps/opik-frontend"
js_files=()
style_files=()
for f in "$@"; do
	case "$f" in
	"$prefix"/*.ts | "$prefix"/*.tsx | "$prefix"/*.js | "$prefix"/*.jsx) js_files+=("${f#"$prefix"/}") ;;
	"$prefix"/*.css | "$prefix"/*.scss | "$prefix"/*.sass) style_files+=("${f#"$prefix"/}") ;;
	esac
done

cd "$prefix"
status=0
if [ "${#js_files[@]}" -gt 0 ]; then
	npx eslint --fix --max-warnings=0 "${js_files[@]}" || status=1
fi
if [ "${#style_files[@]}" -gt 0 ]; then
	npx stylelint --fix "${style_files[@]}" || status=1
fi
exit "$status"
