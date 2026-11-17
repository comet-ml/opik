#!/usr/bin/env bash
# pre-commit hook entry: lint+fix only the frontend files pre-commit passes in
# (changed files), never the whole src tree. Routes by extension: JS/TS → oxlint,
# styles → stylelint; oxfmt formats both. Uses the repo's own node_modules
# (language: system).
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
	npx oxlint --fix --max-warnings=0 "${js_files[@]}" || status=1
fi
if [ "${#style_files[@]}" -gt 0 ]; then
	npx stylelint --fix "${style_files[@]}" || status=1
fi
# Never call oxfmt with no paths: it would format the whole working directory.
files=(${js_files[@]+"${js_files[@]}"} ${style_files[@]+"${style_files[@]}"})
if [ "${#files[@]}" -gt 0 ]; then
	npx oxfmt "${files[@]}" || status=1
fi
exit "$status"
