from __future__ import annotations

import os


def ensure_checkpoint_dir(path: str) -> str:
    os.makedirs(path, exist_ok=True)
    return path
