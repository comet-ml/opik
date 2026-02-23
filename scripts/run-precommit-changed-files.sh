#!/usr/bin/env bash
set -euo pipefail

usage() {
	echo "Usage: $0 --config <path> --pathspec <pathspec> [--label <text>] [--precommit-cmd <cmd>]"
}

config=""
pathspec=""
label="files"
precommit_cmd="pre-commit"

while [[ $# -gt 0 ]]; do
	case "$1" in
	--config)
		config="${2:-}"
		shift 2
		;;
	--pathspec)
		pathspec="${2:-}"
		shift 2
		;;
	--label)
		label="${2:-}"
		shift 2
		;;
	--precommit-cmd)
		precommit_cmd="${2:-}"
		shift 2
		;;
	-h | --help)
		usage
		exit 0
		;;
	*)
		echo "Unknown argument: $1"
		usage
		exit 1
		;;
	esac
done

if [[ -z "$config" || -z "$pathspec" ]]; then
	echo "Error: --config and --pathspec are required."
	usage
	exit 1
fi

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [[ -z "$repo_root" ]]; then
	echo "Error: must run inside a git repository."
	exit 1
fi
cd "$repo_root"

precommit_bin="${precommit_cmd%% *}"
if [[ ! -x "$precommit_bin" ]] && ! command -v "$precommit_bin" >/dev/null 2>&1; then
	echo "Error: pre-commit command '$precommit_cmd' is not available."
	exit 1
fi

changed_files="$(mktemp)"
trap 'rm -f "$changed_files"' EXIT

git diff --name-only -z --diff-filter=ACM -- "$pathspec" > "$changed_files"
if [[ ! -s "$changed_files" ]]; then
	echo "No ${label} changed. Pre-commit skipped."
	exit 0
fi

xargs -0 "$precommit_cmd" run --config "$config" --files < "$changed_files"
