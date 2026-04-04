"""Shared utilities for bridge command handlers."""

import os
import random
import subprocess
import threading
from pathlib import Path
from typing import Optional, Set

from . import CommandError

_BINARY_CHECK_SIZE = 8192


def validate_path(path: str, repo_root: Path) -> Path:
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
    """Re-check realpath inside a lock to prevent symlink TOCTOU."""
    real = os.path.realpath(path)
    real_root = os.path.realpath(repo_root)
    if not real.startswith(real_root + os.sep) and real != real_root:
        raise CommandError("path_traversal", "Path changed to point outside repository")


def is_binary(path: Path) -> bool:
    try:
        with open(path, "rb") as f:
            chunk = f.read(_BINARY_CHECK_SIZE)
        return b"\x00" in chunk
    except OSError:
        return False


def git_ls_files(repo_root: Path) -> Optional[Set[str]]:
    """Return tracked + untracked-not-ignored files, or None if git unavailable."""
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
    wait = min(backoff, cap) * (0.5 + random.random() * 0.5)
    shutdown_event.wait(wait)
