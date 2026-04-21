"""Codebase checklist — local scan shipped on connect."""

import logging
import os
import platform
import re
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Set

from ..cli.pairing import RunnerType
from .bridge_handlers import CommandError, common

LOGGER = logging.getLogger(__name__)

_TREE_MAX_ENTRIES = 1000
_INSTRUMENTATION_MAX_MATCHES = 50

_CODE_EXTENSIONS = {".py", ".js", ".ts", ".tsx", ".mjs"}

_TRACING_PATTERNS = [
    re.compile(r"import opik"),
    re.compile(r"from opik"),
    re.compile(r"@opik\.track"),
    re.compile(r"opik\.track\("),
    re.compile(r"opik\.flush_tracker"),
    re.compile(r'from ["\']opik["\']'),
    re.compile(r"require\(['\"]opik['\"]\)"),
    re.compile(r'from ["\']opik-openai["\']'),
    re.compile(r'from ["\']opik-vercel["\']'),
]

_ENTRYPOINT_PATTERNS = [
    re.compile(r"entrypoint\s*=\s*True"),
    re.compile(r"entrypoint:\s*true"),
]

_CONFIGURATION_PATTERNS = [
    re.compile(r"get_or_create_config\("),
    re.compile(r"create_config\("),
    re.compile(r"getOrCreateConfig\("),
    re.compile(r"createConfig\("),
]

_ALL_PATTERNS = _TRACING_PATTERNS + _ENTRYPOINT_PATTERNS + _CONFIGURATION_PATTERNS


def _git_files(repo_root: Path) -> Optional[Set[str]]:
    try:
        return common.git_ls_files(repo_root)
    except CommandError:
        return None


def has_entrypoint(repo_root: Path) -> bool:
    """Return True if any code file under *repo_root* contains an entrypoint marker."""
    matches = _find_instrumentation(repo_root, _git_files(repo_root), max_matches=None)
    return any(_matches_any(line, _ENTRYPOINT_PATTERNS) for line in matches)


def build_checklist(
    repo_root: Path,
    command: Optional[List[str]],
    runner_type: RunnerType = RunnerType.ENDPOINT,
) -> Dict[str, Any]:
    git_files = _git_files(repo_root)
    file_tree = _build_file_tree(repo_root, git_files)
    matches = _find_instrumentation(repo_root, git_files)

    return {
        "runner_type": runner_type,
        "command": " ".join(command) if command else None,
        "platform": platform.system().lower(),
        "python_executable": sys.executable,
        "file_tree": file_tree,
        "instrumentation": {
            "tracing": any(_matches_any(line, _TRACING_PATTERNS) for line in matches),
            "entrypoint": any(
                _matches_any(line, _ENTRYPOINT_PATTERNS) for line in matches
            ),
            "configuration": any(
                _matches_any(line, _CONFIGURATION_PATTERNS) for line in matches
            ),
        },
        "instrumentation_matches": matches,
    }


def _matches_any(match_line: str, patterns: list) -> bool:
    content = match_line.split(":", 2)[-1] if ":" in match_line else match_line
    return any(p.search(content) for p in patterns)


def _build_file_tree(repo_root: Path, git_files: Optional[Set[str]]) -> str:
    entries: List[str] = []
    dirs_seen: Set[str] = set()

    if git_files is not None:
        for rel in sorted(git_files):
            parts = Path(rel).parts
            for i in range(len(parts) - 1):
                d = str(Path(*parts[: i + 1])) + "/"
                if d not in dirs_seen:
                    dirs_seen.add(d)
                    entries.append(d)
            entries.append(rel)
    else:
        for root, dirnames, filenames in os.walk(repo_root):
            dirnames[:] = [
                d
                for d in sorted(dirnames)
                if not d.startswith(".") and d not in common.WALK_SKIP_DIRS
            ]
            rel_root = Path(root).relative_to(repo_root)
            if str(rel_root) != ".":
                d = str(rel_root) + "/"
                if d not in dirs_seen:
                    dirs_seen.add(d)
                    entries.append(d)
            for f in sorted(filenames):
                if f.startswith("."):
                    continue
                rel = str(rel_root / f) if str(rel_root) != "." else f
                entries.append(rel)

    total = len(entries)
    if total > _TREE_MAX_ENTRIES:
        entries = entries[:_TREE_MAX_ENTRIES]
        entries.append(f"[truncated: {total} total files]")

    return "\n".join(entries)


def _find_instrumentation(
    repo_root: Path,
    git_files: Optional[Set[str]],
    max_matches: Optional[int] = _INSTRUMENTATION_MAX_MATCHES,
) -> List[str]:
    matches: List[str] = []

    if git_files is not None:
        code_files = sorted(f for f in git_files if Path(f).suffix in _CODE_EXTENSIONS)
    else:
        code_files = []
        for root, dirnames, filenames in os.walk(repo_root):
            dirnames[:] = [
                d
                for d in dirnames
                if not d.startswith(".") and d not in common.WALK_SKIP_DIRS
            ]
            for f in filenames:
                if Path(f).suffix in _CODE_EXTENSIONS:
                    rel = str(Path(root).relative_to(repo_root) / f)
                    if rel.startswith("./"):
                        rel = rel[2:]
                    code_files.append(rel)
        code_files.sort()

    for rel in code_files:
        if max_matches is not None and len(matches) >= max_matches:
            break

        fpath = repo_root / rel
        if not fpath.is_file():
            continue

        try:
            data = fpath.read_bytes()[:8192]
            if b"\x00" in data:
                continue
        except OSError:
            continue

        try:
            content = fpath.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue

        for line_num, line in enumerate(content.splitlines(), 1):
            if max_matches is not None and len(matches) >= max_matches:
                break
            for pattern in _ALL_PATTERNS:
                if pattern.search(line):
                    matches.append(f"{rel}:{line_num}:{line.strip()}")
                    break

    return matches
