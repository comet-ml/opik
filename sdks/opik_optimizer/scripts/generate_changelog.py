#!/usr/bin/env python3
"""
Generate changelog entries by analyzing git history of pyproject.toml version changes.

This script:
1. Finds commits that changed the version in pyproject.toml
2. Extracts version numbers from each commit
3. Generates GitHub compare URLs
4. Summarizes changes between versions

Usage:
    python scripts/generate_changelog.py              # Show all version history
    python scripts/generate_changelog.py --since 2.0.0  # Show versions since 2.0.0
    python scripts/generate_changelog.py --format mdx   # Output in Fern MDX table format
"""

import argparse
import re
import subprocess
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path


@dataclass
class VersionEntry:
    version: str
    commit_hash: str
    date: str
    summary: str = ""


REPO_URL = "https://github.com/comet-ml/opik"
PYPROJECT_PATH = "sdks/opik_optimizer/pyproject.toml"


def run_git_command(args: list[str], cwd: Path | None = None) -> str:
    """Run a git command and return stdout."""
    result = subprocess.run(
        ["git"] + args,
        capture_output=True,
        text=True,
        cwd=cwd,
    )
    if result.returncode != 0:
        raise RuntimeError(f"Git command failed: {result.stderr}")
    return result.stdout.strip()


def get_repo_root() -> Path:
    """Get the git repository root directory."""
    script_dir = Path(__file__).parent
    # Navigate up to find .git directory
    current = script_dir
    while current != current.parent:
        if (current / ".git").exists():
            return current
        current = current.parent
    raise RuntimeError("Not in a git repository")


def extract_version_from_content(content: str) -> str | None:
    """Extract version string from pyproject.toml content."""
    match = re.search(r'^version\s*=\s*["\']([^"\']+)["\']', content, re.MULTILINE)
    return match.group(1) if match else None


def get_file_at_commit(commit: str, filepath: str, repo_root: Path) -> str | None:
    """Get file content at a specific commit."""
    try:
        return run_git_command(["show", f"{commit}:{filepath}"], cwd=repo_root)
    except RuntimeError:
        return None


def get_version_commits(repo_root: Path, limit: int = 50) -> list[VersionEntry]:
    """Find commits that changed the version in pyproject.toml."""
    # Get commits that touched pyproject.toml
    log_output = run_git_command(
        [
            "log",
            "--oneline",
            "--format=%H|%ad|%s",
            "--date=short",
            f"-{limit}",
            "--",
            PYPROJECT_PATH,
        ],
        cwd=repo_root,
    )

    if not log_output:
        return []

    entries = []
    seen_versions: set[str] = set()
    prev_version: str | None = None

    for line in log_output.strip().split("\n"):
        if not line:
            continue

        parts = line.split("|", 2)
        if len(parts) < 3:
            continue

        commit_hash, date, message = parts
        commit_hash = commit_hash.strip()

        # Get version at this commit
        content = get_file_at_commit(commit_hash, PYPROJECT_PATH, repo_root)
        if not content:
            continue

        version = extract_version_from_content(content)
        if not version:
            continue

        # Skip if we've already seen this version (we want the first commit for each version)
        if version in seen_versions:
            continue

        # Check if version actually changed from previous
        if prev_version is None or version != prev_version:
            seen_versions.add(version)
            entries.append(
                VersionEntry(
                    version=version,
                    commit_hash=commit_hash,
                    date=date,
                    summary=message,
                )
            )
            prev_version = version

    return entries


def get_commits_between(
    repo_root: Path, from_hash: str, to_hash: str
) -> list[tuple[str, str]]:
    """Get commit messages between two hashes (for the optimizer subdirectory only)."""
    try:
        log_output = run_git_command(
            [
                "log",
                "--oneline",
                "--format=%h|%s",
                f"{from_hash}..{to_hash}",
                "--",
                "sdks/opik_optimizer/",
            ],
            cwd=repo_root,
        )
        if not log_output:
            return []

        commits = []
        for line in log_output.strip().split("\n"):
            if "|" in line:
                hash_short, message = line.split("|", 1)
                commits.append((hash_short.strip(), message.strip()))
        return commits
    except RuntimeError:
        return []


def generate_compare_url(from_hash: str, to_hash: str) -> str:
    """Generate GitHub compare URL."""
    return f"{REPO_URL}/compare/{from_hash}...{to_hash}"


def format_mdx_table(entries: list[VersionEntry], repo_root: Path) -> str:
    """Format version entries as Fern MDX table."""
    lines = [
        "| Date | Version | Highlights |",
        "| --- | --- | --- |",
    ]

    for i, entry in enumerate(entries):
        # Get the previous version's commit for comparison
        if i + 1 < len(entries):
            prev_hash = entries[i + 1].commit_hash
            compare_url = generate_compare_url(prev_hash, entry.commit_hash)
            link = f"[Commits →]({compare_url})"
        else:
            # First version - link to single commit
            link = f"[Commit →]({REPO_URL}/commit/{entry.commit_hash})"

        # Get commits between versions for summary
        if i + 1 < len(entries):
            commits = get_commits_between(
                repo_root, entries[i + 1].commit_hash, entry.commit_hash
            )
            if commits and len(commits) <= 10:
                # Summarize commit messages directly
                summary = summarize_commits(commits)
            elif commits:
                # Extract themes for large releases
                summary = extract_themes_from_commits(commits, entry.version)
            else:
                summary = f"Version {entry.version} release."
        else:
            summary = clean_commit_message(entry.summary)

        # Format date
        date_str = f"**{entry.date}**"

        # Build highlights with link
        highlights = f"{summary} {link}"

        lines.append(f"| {date_str} | `{entry.version}` | {highlights} |")

    return "\n".join(lines)


def clean_commit_message(msg: str, linkify_prs: bool = True) -> str:
    """Clean up commit message prefixes like [NA][SDK]."""
    # Remove [TAG] prefixes
    msg = re.sub(r"^\s*\[[^\]]+\]\s*", "", msg)
    msg = re.sub(r"^\s*\[[^\]]+\]\s*", "", msg)  # Run twice for [NA][SDK] style
    # Clean up common prefixes
    for prefix in ["chore:", "feat:", "fix:", "docs:", "refactor:", "test:"]:
        if msg.lower().startswith(prefix):
            msg = msg[len(prefix) :].strip()
    # Remove "Optimizer" prefix (common in commit messages)
    if msg.startswith("Optimizer "):
        msg = msg[10:]
    if msg.startswith("Opik Optimizer "):
        msg = msg[15:]
    # Capitalize first letter
    if msg and msg[0].islower():
        msg = msg[0].upper() + msg[1:]
    # Convert PR numbers to links
    if linkify_prs:
        msg = re.sub(r"\(#(\d+)\)", rf"[#\1]({REPO_URL}/pull/\1)", msg)
    return msg.strip()


def summarize_commits(commits: list[tuple[str, str]], max_items: int = 5) -> str:
    """Create a summary from commit messages."""
    # Filter out merge commits and clean up messages
    messages = []
    for _, msg in commits:
        # Skip merge commits
        if msg.startswith("Merge"):
            continue
        # Clean up the message
        cleaned = clean_commit_message(msg)
        if cleaned:
            messages.append(cleaned)

    if not messages:
        return "Various improvements and fixes."

    # Take first few unique messages
    unique_messages = list(dict.fromkeys(messages))[:max_items]
    return ", ".join(unique_messages) + "."


def extract_themes_from_commits(
    commits: list[tuple[str, str]], version: str
) -> str:
    """Extract key themes from a large set of commits."""
    # Collect all cleaned messages
    messages = []
    for _, msg in commits:
        if msg.startswith("Merge"):
            continue
        cleaned = clean_commit_message(msg, linkify_prs=False)
        if cleaned:
            messages.append(cleaned.lower())

    if not messages:
        return f"Version {version} release."

    # Count keyword occurrences to identify themes
    themes = []
    keywords = {
        "validation": "validation dataset support",
        "benchmark": "benchmark improvements",
        "agent": "agent support enhancements",
        "multimodal": "multimodal support",
        "test": "test infrastructure updates",
        "fix": "bug fixes",
        "refactor": "code refactoring",
        "mcp": "MCP tooling updates",
        "cache": "caching improvements",
        "dataset": "dataset handling improvements",
        "gepa": "GEPA optimizer updates",
        "hierarchical": "Hierarchical Reflective Optimizer updates",
        "fewshot": "Few-Shot Optimizer updates",
        "evolutionary": "Evolutionary Optimizer updates",
        "metaprompt": "MetaPrompt Optimizer updates",
    }

    for keyword, theme in keywords.items():
        if any(keyword in m for m in messages):
            themes.append(theme)
            if len(themes) >= 3:
                break

    if themes:
        return ", ".join(themes).capitalize() + "."
    return f"Version {version} release with various improvements."


def format_plain(entries: list[VersionEntry], repo_root: Path) -> str:
    """Format version entries as plain text."""
    lines = []
    for i, entry in enumerate(entries):
        lines.append(f"\n{'='*60}")
        lines.append(f"Version: {entry.version}")
        lines.append(f"Date: {entry.date}")
        lines.append(f"Commit: {entry.commit_hash}")

        if i + 1 < len(entries):
            prev_hash = entries[i + 1].commit_hash
            lines.append(f"Compare: {generate_compare_url(prev_hash, entry.commit_hash)}")

            # Show commits in this version
            commits = get_commits_between(repo_root, prev_hash, entry.commit_hash)
            if commits:
                lines.append(f"\nCommits ({len(commits)}):")
                for hash_short, msg in commits[:15]:
                    lines.append(f"  {hash_short} {msg}")
                if len(commits) > 15:
                    lines.append(f"  ... and {len(commits) - 15} more")
        else:
            lines.append(f"Commit URL: {REPO_URL}/commit/{entry.commit_hash}")

    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate changelog entries from git history"
    )
    parser.add_argument(
        "--since",
        type=str,
        help="Only show versions since this version (e.g., 2.0.0)",
    )
    parser.add_argument(
        "--format",
        choices=["plain", "mdx"],
        default="plain",
        help="Output format (default: plain)",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=50,
        help="Maximum commits to search (default: 50)",
    )
    args = parser.parse_args()

    repo_root = get_repo_root()
    print(f"Repository root: {repo_root}")
    print(f"Scanning {PYPROJECT_PATH} for version changes...\n")

    entries = get_version_commits(repo_root, limit=args.limit)

    if not entries:
        print("No version entries found.")
        return

    # Filter by --since if provided
    if args.since:
        filtered = []
        for entry in entries:
            filtered.append(entry)
            if entry.version == args.since:
                break
        entries = filtered

    print(f"Found {len(entries)} version(s):\n")

    if args.format == "mdx":
        print(format_mdx_table(entries, repo_root))
    else:
        print(format_plain(entries, repo_root))

    print("\n" + "=" * 60)
    print("To update the changelog, copy the relevant entries to:")
    print(
        "  apps/opik-documentation/documentation/fern/docs/agent_optimization/getting_started/changelog.mdx"
    )


if __name__ == "__main__":
    main()
