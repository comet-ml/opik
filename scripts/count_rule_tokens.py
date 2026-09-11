#!/usr/bin/env python3
"""
Count tokens in .agents/rules files, organized by folder.

Usage:
    python scripts/count_rule_tokens.py

Requires:
    pip install tiktoken
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

import tiktoken

REPORT_WIDTH = 68
FOLDER_PRIORITY = [
    "root",
    "apps/opik-backend",
    "apps/opik-frontend",
    "sdks/python",
    "sdks/typescript",
    "sdks/opik_optimizer",
]
CONTEXT_WINDOWS = {
    "GPT-4o (128K)": 128_000,
    "Claude Opus (200K)": 200_000,
}


@dataclass
class RuleFile:
    name: str
    tokens: int
    always_apply: bool


@dataclass
class FolderStats:
    path: str
    files: list[RuleFile] = field(default_factory=list)

    @property
    def total_tokens(self) -> int:
        return sum(f.tokens for f in self.files)

    @property
    def display_path(self) -> str:
        return ".agents/rules/" if self.path == "root" else f".agents/rules/{self.path}"


def parse_always_apply(content: str) -> bool:
    """Parse alwaysApply from YAML frontmatter. Defaults to True."""
    if not content.startswith("---"):
        return True

    end = re.search(r"\n---\s*\n", content)
    if not end:
        return True

    match = re.search(r"alwaysApply:\s*(true|false)", content[: end.end()], re.IGNORECASE)
    return match.group(1).lower() == "true" if match else True


def scan_rules(rules_dir: Path) -> dict[str, FolderStats]:
    """Scan all .mdc files and return stats grouped by folder."""
    encoding = tiktoken.encoding_for_model("gpt-4")
    folders: dict[str, FolderStats] = {}

    for path in sorted(rules_dir.rglob("*.mdc")):
        content = path.read_text(encoding="utf-8", errors="ignore")
        rel = path.relative_to(rules_dir)
        folder_name = str(rel.parent) if rel.parent != Path(".") else "root"

        if folder_name not in folders:
            folders[folder_name] = FolderStats(path=folder_name)

        folders[folder_name].files.append(
            RuleFile(
                name=path.name,
                tokens=len(encoding.encode(content)),
                always_apply=parse_always_apply(content),
            )
        )

    return folders


def sorted_folders(folders: dict[str, FolderStats]) -> list[FolderStats]:
    """Sort folders with priority list first, then alphabetically."""
    by_name = {f.path: f for f in folders.values()}
    prioritized = [by_name[p] for p in FOLDER_PRIORITY if p in by_name]
    remaining = sorted(
        [f for f in folders.values() if f.path not in FOLDER_PRIORITY],
        key=lambda f: f.path,
    )
    return prioritized + remaining


def print_report(folders: dict[str, FolderStats]) -> None:
    """Print formatted token count report."""
    w = REPORT_WIDTH

    print("=" * w)
    print("TOKEN COUNT REPORT - .agents/rules")
    print("=" * w)
    print()

    grand_total = 0

    for folder in sorted_folders(folders):
        print(f"📁 {folder.display_path}")
        print("-" * w)
        print(f"   {'File':<40}{'Always Apply':<14}{'Tokens':>10}")
        print(f"   {'-'*40}{'-'*14}{'-'*10}")

        for f in sorted(folder.files, key=lambda x: -x.tokens):
            print(f"   {f.name:<40}{'Yes' if f.always_apply else 'No':<14}{f.tokens:>10,}")

        print(f"   {'-'*40}{'-'*14}{'-'*10}")
        print(f"   {'SUBTOTAL':<54}{folder.total_tokens:>10,}")
        print()

        grand_total += folder.total_tokens

    print("=" * w)
    print(f"{'GRAND TOTAL':<51}{grand_total:>10,} tokens")
    print("=" * w)

    print()
    print("📊 Context Window Usage:")
    for name, size in CONTEXT_WINDOWS.items():
        print(f"   {name:<20} {grand_total / size * 100:>5.1f}% of context window")


def main() -> int:
    rules_dir = Path(__file__).parent.parent / ".agents" / "rules"

    if not rules_dir.exists():
        print(f"Error: Rules directory not found at {rules_dir}")
        return 1

    print(f"Scanning: {rules_dir}")
    print()

    print_report(scan_rules(rules_dir))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
