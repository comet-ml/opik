#!/usr/bin/env python3
"""Print the hook ids whose repo `rev:` or hook `entry:` differs between two
pre-commit configs.

The weekly autobump (precommit_autoupdate.yml) re-scans the whole repo with only
the hooks `pre-commit autoupdate` just bumped — a new linter version can add
rules that fail files no diff touched, while unchanged hooks have nothing new to
say. Ids are de-duplicated: the same hook id recurs once per scope, and
`pre-commit run <id> --all-files` already runs every hook sharing it.

Repos are compared positionally: autoupdate only rewrites `rev:` lines, so both
configs list the same repos in the same order. A repo also counts as bumped
when only a hook's `entry:` changed (e.g. an image tag re-synced below), so the
config change never goes un-rescanned. Local repos have no rev and are never
bumped.

With --sync-entry-tags, also rewrites <new-config> in place so every remote hook
that pins a container image in `entry:` (image:tag) carries the tag equal to
its repo's rev. `language: docker_image` hooks run the image named in `entry:`,
not the rev — upstream hadolint's entry is untagged, so without our pinned entry
it floats to :latest whatever the rev says, and a bump would change nothing.

Usage:
    precommit-bumped-hooks.py [--sync-entry-tags] <old-config> <new-config>

Prints one hook id per line, in config order.
"""
import re
import sys

import yaml

ENTRY_IMAGE_RE = re.compile(r"^(\s*entry:\s*)([^\s:]+/[^\s:]+):(\S+)(.*)$")


def bumped_hook_ids(old_config, new_config):
    old_repos, new_repos = old_config["repos"], new_config["repos"]
    if [r["repo"] for r in old_repos] != [r["repo"] for r in new_repos]:
        sys.exit("error: configs list different repos; expected only rev: changes")
    ids = []
    for old, new in zip(old_repos, new_repos):
        old_entries = [h.get("entry") for h in old.get("hooks", [])]
        new_entries = [h.get("entry") for h in new.get("hooks", [])]
        if old.get("rev") == new.get("rev") and old_entries == new_entries:
            continue
        for hook in new.get("hooks", []):
            if hook["id"] not in ids:
                ids.append(hook["id"])
    return ids


# Line-based so comments and formatting survive (a YAML round-trip drops them).
def sync_entry_tags(text):
    rev = None
    out = []
    for line in text.splitlines(keepends=True):
        if re.match(r"^\s*- repo:", line):
            rev = None
        elif m := re.match(r"""^\s*rev:\s*['"]?([^'"\s]+)""", line):
            rev = m.group(1)
        elif rev:
            line = ENTRY_IMAGE_RE.sub(rf"\g<1>\g<2>:{rev}\g<4>", line)
        out.append(line)
    return "".join(out)


def main(argv):
    sync = "--sync-entry-tags" in argv
    paths = [a for a in argv if a != "--sync-entry-tags"]
    if len(paths) != 2:
        sys.exit(__doc__)
    old_path, new_path = paths
    with open(old_path) as f:
        old_config = yaml.safe_load(f)
    with open(new_path) as f:
        text = f.read()
    synced = sync_entry_tags(text) if sync else text
    # Parse and validate before writing, so a bad config is never left rewritten.
    ids = bumped_hook_ids(old_config, yaml.safe_load(synced))
    if synced != text:
        with open(new_path, "w") as f:
            f.write(synced)
    for hook_id in ids:
        print(hook_id)


if __name__ == "__main__":
    main(sys.argv[1:])
