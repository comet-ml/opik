"""Shared utilities for bridge command handlers."""

import os
import random
import subprocess
import threading
from pathlib import Path
from typing import Optional, Set, Tuple

from . import CommandError

_BINARY_CHECK_SIZE = 8192

WALK_SKIP_DIRS = frozenset(
    {
        "node_modules",
        "__pycache__",
        ".venv",
        "venv",
        ".tox",
        ".mypy_cache",
        ".pytest_cache",
        ".ruff_cache",
        "dist",
        "build",
    }
)


def validate_path(path: str, repo_root: Path) -> Path:
    """Resolve a relative or absolute path against repo_root, ensuring it stays
    within the repository. Rejects empty paths, '..' segments, and symlinks that
    resolve outside the root."""
    if not path:
        raise CommandError("path_traversal", "Empty path")

    real_root = os.path.realpath(repo_root)
    resolved = os.path.realpath(os.path.join(real_root, path))

    if not resolved.startswith(real_root + os.sep) and resolved != real_root:
        raise CommandError("path_traversal", f"Path escapes repository root: {path}")

    if ".." in Path(path).parts:
        raise CommandError("path_traversal", f"Path contains '..': {path}")

    return Path(resolved)


def revalidate_path(path: Path, repo_root: Path) -> None:
    """Re-check realpath inside a mutation lock to catch symlink TOCTOU races
    where a symlink target changes between initial validation and file I/O."""
    real = os.path.realpath(path)
    real_root = os.path.realpath(repo_root)
    if not real.startswith(real_root + os.sep) and real != real_root:
        raise CommandError("path_traversal", "Path changed to point outside repository")


def is_binary(path: Path) -> bool:
    """Check first 8KB for null bytes to detect binary files."""
    try:
        with open(path, "rb") as f:
            chunk = f.read(_BINARY_CHECK_SIZE)
        return b"\x00" in chunk
    except OSError:
        return False


def resolve_text_file(path_str: str, repo_root: Path) -> Tuple[Path, str]:
    """Validate path, check it exists, is not binary, and read as UTF-8.

    Returns (resolved_path, file_content_as_str). Reads raw bytes and decodes
    to preserve original line endings (CRLF etc).
    """
    path = validate_path(path_str, repo_root)

    if not path.exists():
        raise CommandError("file_not_found", f"File not found: {path_str}")

    if not path.is_file():
        raise CommandError("file_not_found", f"Not a file: {path_str}")

    if is_binary(path):
        raise CommandError("binary_file", f"Binary file: {path_str}")

    try:
        raw = path.read_bytes().decode("utf-8")
    except UnicodeDecodeError:
        raise CommandError("binary_file", f"File is not valid UTF-8: {path_str}")

    return path, raw


def git_ls_files(repo_root: Path) -> Optional[Set[str]]:
    """Return all git-visible files (tracked + untracked non-ignored) as relative
    paths. Returns None if git is unavailable or the directory isn't a repo."""
    try:
        tracked = subprocess.run(
            ["git", "ls-files"],
            cwd=str(repo_root),
            capture_output=True,
            text=True,
            timeout=10,
        )
        if tracked.returncode != 0:
            return None

        untracked = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard"],
            cwd=str(repo_root),
            capture_output=True,
            text=True,
            timeout=10,
        )

        files = set()
        for line in tracked.stdout.splitlines():
            if line.strip():
                files.add(line.strip())
        for line in untracked.stdout.splitlines():
            if line.strip():
                files.add(line.strip())
        return files
    except (subprocess.TimeoutExpired, FileNotFoundError):
        return None


def backoff_wait(
    shutdown_event: threading.Event, backoff: float, cap: float = 30.0
) -> None:
    """Sleep with jitter, interruptible by the shutdown event.

    Waits between 50-100% of the backoff value, capped at ``cap`` seconds.
    """
    wait = min(backoff, cap) * (0.5 + random.random() * 0.5)
    shutdown_event.wait(wait)
