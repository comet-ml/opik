#!/usr/bin/env bash
set -euo pipefail

usage() {
	echo "Usage: $0 --pathspec <pathspec> [--config <path>] [--label <text>] [--base-ref <ref>] [--precommit-cmd <cmd>] [--print-files]"
}

config=""
pathspec=""
label="files"
base_ref=""
precommit_cmd="pre-commit"
print_files="false"

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
	--base-ref)
		base_ref="${2:-}"
		shift 2
		;;
	--precommit-cmd)
		precommit_cmd="${2:-}"
		shift 2
		;;
	--print-files)
		print_files="true"
		shift
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

if [[ -z "$pathspec" ]]; then
	echo "Error: --pathspec is required."
	usage
	exit 1
fi

if [[ "$print_files" != "true" ]] && [[ -z "$config" ]]; then
	echo "Error: --config is required unless --print-files is used."
	usage
	exit 1
fi

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [[ -z "$repo_root" ]]; then
	echo "Error: must run inside a git repository."
	exit 1
fi
cd "$repo_root"

if [[ "$print_files" != "true" ]]; then
	precommit_bin="${precommit_cmd%% *}"
	if [[ ! -x "$precommit_bin" ]] && ! command -v "$precommit_bin" >/dev/null 2>&1; then
		echo "Error: pre-commit command '$precommit_cmd' is not available."
		exit 1
	fi
fi

raw_changed_files="$(mktemp)"
changed_files="$(mktemp)"
trap 'rm -f "$raw_changed_files" "$changed_files"' EXIT

# 1) Staged/index changes.
git diff --name-only --diff-filter=ACM --cached -- "$pathspec" >> "$raw_changed_files"
# 2) Working-tree changes.
git diff --name-only --diff-filter=ACM -- "$pathspec" >> "$raw_changed_files"

# 3) Branch commits against base ref when provided.
if [[ -n "$base_ref" ]]; then
	if ! git rev-parse --verify "$base_ref" >/dev/null 2>&1; then
		case "$base_ref" in
		origin/*)
			base_branch="${base_ref#origin/}"
			git fetch -q origin "$base_branch:refs/remotes/origin/$base_branch" || true
			;;
		esac
	fi

	if git rev-parse --verify "$base_ref" >/dev/null 2>&1; then
		merge_base="$(git merge-base HEAD "$base_ref")"
		git diff --name-only --diff-filter=ACM "$merge_base...HEAD" -- "$pathspec" >> "$raw_changed_files"
	fi
fi

sed '/^$/d' "$raw_changed_files" | sort -u > "$changed_files"

if [[ ! -s "$changed_files" ]]; then
	echo "No ${label} changed. Pre-commit skipped."
	exit 0
fi

if [[ "$print_files" == "true" ]]; then
	cat "$changed_files"
	exit 0
fi

tr '\n' '\0' < "$changed_files" | xargs -0 "$precommit_cmd" run --config "$config" --files
