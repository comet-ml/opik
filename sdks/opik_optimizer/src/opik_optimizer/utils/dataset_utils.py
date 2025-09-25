from __future__ import annotations

import hashlib
import secrets
import time
from functools import lru_cache
from importlib import resources
from typing import Any
from collections.abc import Iterable


@lru_cache(maxsize=None)
def dataset_suffix(package: str, filename: str) -> str:
    """Return a stable checksum-based suffix for a JSONL dataset file."""
    text = resources.files(package).joinpath(filename).read_text(encoding="utf-8")
    return hashlib.md5(text.encode("utf-8")).hexdigest()[:8]


def generate_uuid7_str() -> str:
    """Generate a UUIDv7-compatible string, emulating the layout if unavailable."""
    import uuid

    if hasattr(uuid, "uuid7"):
        return str(uuid.uuid7())  # type: ignore[attr-defined]

    unix_ts_ms = int(time.time() * 1000) & ((1 << 48) - 1)
    rand_a = secrets.randbits(12)
    rand_b = secrets.randbits(62)

    uuid_int = unix_ts_ms << 80
    uuid_int |= 0x7 << 76  # version 7
    uuid_int |= rand_a << 64
    uuid_int |= 0b10 << 62  # RFC4122 variant
    uuid_int |= rand_b

    return str(uuid.UUID(int=uuid_int))


def attach_uuids(records: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    """Copy records and assign a fresh UUIDv7 `id` to each."""
    payload: list[dict[str, Any]] = []
    for record in records:
        rec = dict(record)
        rec["id"] = generate_uuid7_str()
        payload.append(rec)
    return payload


__all__ = ["dataset_suffix", "generate_uuid7_str", "attach_uuids"]
