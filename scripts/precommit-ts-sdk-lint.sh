#!/usr/bin/env bash
# pre-commit hook entry: eslint --fix only the TypeScript SDK files pre-commit
# passes in (changed files). Runs from the SDK dir so its eslint config + own
# node_modules resolve (language: system).
set -euo pipefail

[ "$#" -eq 0 ] && exit 0

prefix="sdks/typescript"
files=()
for f in "$@"; do
	files+=("${f#"$prefix"/}")
done

cd "$prefix"
# --no-warn-ignored: pre-commit may pass files the eslint config ignores (e.g. the
# generated src/opik/rest_api/**). ESLint already skips them; this suppresses the
# noisy "File ignored …" warning it would otherwise print per file.
exec npx eslint --fix --no-warn-ignored "${files[@]}"
