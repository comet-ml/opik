"""Path validation and binary detection for bridge command handlers."""

import fnmatch
import os
from pathlib import Path

from . import CommandError

SENSITIVE_PATTERNS = [".env", ".env.*", "*.pem", "*.key", "*secret*", "*credential*"]

_BINARY_CHECK_SIZE = 8192


def validate_path(path: str, repo_root: Path) -> Path:
    """Resolve path relative to repo_root. Raises CommandError on traversal or sensitive file."""
    if not path:
        raise CommandError("path_traversal", "Empty path")

    real_root = os.path.realpath(repo_root)
    resolved = os.path.realpath(os.path.join(real_root, path))

    if not resolved.startswith(real_root + os.sep) and resolved != real_root:
        raise CommandError("path_traversal", f"Path escapes repository root: {path}")

    if ".." in Path(path).parts:
        raise CommandError("path_traversal", f"Path contains '..': {path}")

    name = os.path.basename(resolved)
    for pattern in SENSITIVE_PATTERNS:
        if fnmatch.fnmatch(name, pattern) or fnmatch.fnmatch(
            name.lower(), pattern.lower()
        ):
            raise CommandError(
                "sensitive_path", f"Access to sensitive file blocked: {name}"
            )

    return Path(resolved)


def is_binary(path: Path) -> bool:
    """Check first 8KB for null bytes."""
    try:
        with open(path, "rb") as f:
            chunk = f.read(_BINARY_CHECK_SIZE)
        return b"\x00" in chunk
    except OSError:
        return False
