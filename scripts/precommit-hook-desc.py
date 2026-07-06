#!/usr/bin/env python3
"""Resolve pre-commit hook display names to their descriptions.

Single matcher for the Code Quality timing comment: both the ran-table
(precommit-timing-table.sh) and the skipped-table (precommit-skipped-table.sh)
pipe their hook names here so the keyword→description mapping and its ordering
live in exactly one place. The description DATA lives in the shared
precommit-hook-descriptions.tsv next to this script; this is the matching LOGIC.

Reads hook display names from stdin (one per line) and writes `name<TAB>desc`
for each (desc empty if no keyword matches). Keyword is matched as a substring;
TSV file order is honoured (most specific first, e.g. ruff-format before ruff).
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TSV = os.path.join(HERE, "precommit-hook-descriptions.tsv")


def load_map(path):
    pairs = []
    with open(path) as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line or line.startswith("#") or "\t" not in line:
                continue
            kw, desc = line.split("\t", 1)
            pairs.append((kw, desc))
    return pairs


def describe(name, pairs):
    for kw, desc in pairs:
        if kw in name:
            return desc
    return ""


def main():
    pairs = load_map(TSV)
    for line in sys.stdin:
        name = line.rstrip("\n")
        if not name:
            continue
        sys.stdout.write("%s\t%s\n" % (name, describe(name, pairs)))


if __name__ == "__main__":
    main()
