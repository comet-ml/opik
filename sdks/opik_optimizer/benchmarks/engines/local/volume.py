from __future__ import annotations

import os


def ensure_checkpoint_dir(path: str) -> str:
    """Ensure the local checkpoint directory exists and return its path."""
    # TODO(benchmarks): replace this helper with a local storage sink adapter
    # so local/modal engines share the same storage abstraction.
    os.makedirs(path, exist_ok=True)
    return path
