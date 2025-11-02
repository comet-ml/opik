"""
Storage backends for checkpoint bundles.
"""

from __future__ import annotations

import gzip
import json
import hashlib
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol


class StorageProvider(Protocol):
    """Minimal interface for checkpoint persistence."""

    def save(self, path: Path, data: bytes) -> None:  # pragma: no cover - interface
        ...

    def load(self, path: Path) -> bytes:  # pragma: no cover - interface
        ...


@dataclass(slots=True)
class LocalStorageProvider:
    """
    Simple filesystem storage that optionally applies gzip compression.
    """

    compression: str = "gzip"

    def save(self, path: Path, data: bytes) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        if self.compression == "gzip":
            with gzip.open(path, "wb") as fh:
                fh.write(data)
        else:
            with path.open("wb") as fh:
                fh.write(data)

    def load(self, path: Path) -> bytes:
        if self.compression == "gzip":
            with gzip.open(path, "rb") as fh:
                return fh.read()
        with path.open("rb") as fh:
            return fh.read()


def compute_sha256(*components: bytes) -> str:
    """
    Compute a hex digest across one or more byte payloads.
    """
    digest = hashlib.sha256()
    for chunk in components:
        digest.update(chunk)
    return digest.hexdigest()


def dumps_json(data: dict) -> bytes:
    """
    Serialize a dictionary to UTF-8 encoded JSON.
    """
    return json.dumps(data, sort_keys=True, separators=(",", ":")).encode("utf-8")
