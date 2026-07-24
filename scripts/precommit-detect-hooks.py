#!/usr/bin/env python3
"""Emit the pre-commit hooks that would run on a set of changed files, as a
GitHub Actions matrix.

pre-commit has no native "list the hooks that would run" command, so the CI
matrix can't be built from it directly. This derives that set by replaying
pre-commit's own path-matching: for each hook, a file is in-scope when it
matches the global+hook `files:` regex and does not match the global+hook
`exclude:` regex. A hook is emitted when at least one changed file is in-scope.

Each emitted leg is one hook, so CI runs one job per linter that actually has
work — independent reporting, retry, and visibility, with no skip-spam jobs for
the linters that match nothing.

Hook ids are not unique in this config (trailing-whitespace, ruff, mypy, etc.
recur once per scope), so a leg is targeted by `pre-commit run <id> --files
<its matched files>` rather than by id alone — id + files isolates exactly one
hook (siblings sharing the id report "no files to check"). `files` therefore
carries that hook's matched paths.

`toolchain` tells the matrix job which heavy runtime to provision (java/node-fe/
node-ts) or none (python hooks self-provision in pre-commit's isolated envs).

Usage:
    git diff --name-only BASE..HEAD | precommit-detect-hooks.py <config>
    precommit-detect-hooks.py <config> <changed-file> [changed-file ...]

Prints {"legs": [{name, id, files, toolchain}, ...]} as a single JSON line.

Models `files:`/`exclude:` only — the path-dependent part of matching. Every
hook in our config gates by an explicit `files:` regex, and `types:` can only
narrow further, so path matching is sufficient. A hook with `types:` but no
`files:` would be over-matched here; we fail loudly on that below so the gap
can't silently regress.
"""
import json
import re
import sys

import yaml

DEFAULT_FILES = ""        # pre-commit default: match everything
DEFAULT_EXCLUDE = "^$"     # pre-commit default: match nothing

# Map a hook to the heavy runtime its leg must provision. Python hooks need
# nothing (pre-commit builds them in isolated envs); actionlint is language:
# golang (self-built); hygiene hooks are pure-python. Only the repo-local
# system hooks that shell out to mvn/npm need a toolchain.
TOOLCHAIN_BY_ID = {
    "spotless": "java",
    "fe-eslint": "node-fe",
    "fe-typecheck": "node-fe",
    "no-private-fe-plugins": "none",  # plain bash, but gated to FE paths
    "ts-sdk-eslint": "node-ts",
    "ts-sdk-typecheck": "node-ts",
}

# Some hooks carry a `types:` filter (often defined upstream in the hook repo's
# .pre-commit-hooks.yaml, so it's invisible in our config) that narrows their
# `files:` match to a content type. We model that narrowing here so detect
# doesn't emit a leg for, e.g., ruff on a Makefile that merely lives under
# sdks/opik_optimizer — at runtime pre-commit would Skip it ("no files to
# check") and the leg would do nothing. Value is the set of path suffixes the
# hook actually acts on. Keep in sync with the python tool hooks in the config.
TYPED_IDS = {
    "ruff": (".py", ".pyi"),
    "ruff-format": (".py", ".pyi"),
    "mypy": (".py", ".pyi"),
    "pyupgrade": (".py", ".pyi"),
    "radon-cc": (".py",),
    "radon-raw": (".py",),
    "xenon": (".py",),
    "lizard": (".py",),
    "vulture": (".py",),
    # check-* hooks carry an upstream `types:` for their format.
    "check-yaml": (".yaml", ".yml"),
    "check-json": (".json",),
    "check-toml": (".toml",),
}


def hook_matches(files_re, exclude_re, changed, suffixes=None):
    fpat = re.compile(files_re) if files_re else None
    xpat = re.compile(exclude_re) if exclude_re else None
    matched = []
    for path in changed:
        if fpat is not None and not fpat.search(path):
            continue
        if xpat is not None and xpat.search(path):
            continue
        if suffixes is not None and not path.endswith(suffixes):
            continue
        matched.append(path)
    return matched


def main(argv):
    config_path = argv[1]
    files_args = [a for a in argv[2:] if a]
    changed = files_args or [ln.strip() for ln in sys.stdin if ln.strip()]

    with open(config_path) as fh:
        config = yaml.safe_load(fh)

    global_files = config.get("files", DEFAULT_FILES)
    global_exclude = config.get("exclude", DEFAULT_EXCLUDE)

    legs = []
    skipped = []
    for repo in config.get("repos", []):
        for hook in repo.get("hooks", []):
            hook_files = hook.get("files", global_files)
            hook_exclude = hook.get("exclude", global_exclude)

            if not hook_files and ("types" in hook or "types_or" in hook):
                raise SystemExit(
                    f"hook '{hook.get('id')}' uses types: without files:; "
                    "path-only detection would over-match it. Add a files: "
                    "regex or extend precommit-detect-hooks.py."
                )

            hook_id = hook["id"]
            name = hook.get("name", hook_id)
            suffixes = TYPED_IDS.get(hook_id)
            matched = hook_matches(hook_files, hook_exclude, changed, suffixes)
            if not matched:
                # No matching files → no CI job. Recorded so the summary can
                # list it as a skipped check (coverage transparency).
                skipped.append({"name": name, "id": hook_id})
                continue

            legs.append(
                {
                    "name": name,
                    "id": hook_id,
                    "files": " ".join(matched),
                    "toolchain": TOOLCHAIN_BY_ID.get(hook_id, "none"),
                }
            )

    print(json.dumps({"legs": legs, "skipped": skipped}))


if __name__ == "__main__":
    main(sys.argv)
