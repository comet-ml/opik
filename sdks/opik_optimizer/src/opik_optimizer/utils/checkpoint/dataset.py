"""
Dataset checksum helpers for checkpoint validation.
"""

from __future__ import annotations

import hashlib
from typing import Iterable, Mapping


def checksum_from_items(
    items: Iterable[Mapping[str, object]], key: str = "id"
) -> str:
    """
    Compute a checksum from dataset items using the provided key.
    """
    digest = hashlib.sha256()
    for item in items:
        value = str(item.get(key, "")).encode("utf-8")
        digest.update(value + b"\0")
    return digest.hexdigest()
