"""
Pydantic models describing checkpoint metadata and payloads.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Dict

from pydantic import BaseModel, Field


SCHEMA_VERSION = "1.0.0"


class CheckpointMetadata(BaseModel):
    """
    Stable metadata describing a checkpoint bundle.
    """

    schema_version: str = Field(default=SCHEMA_VERSION)
    optimizer: str
    optimizer_version: str
    project_name: str | None = None
    dataset_id: str | None = None
    dataset_name: str | None = None
    dataset_checksum: str | None = None
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    run_id: str
    round_index: int
    random_state_fingerprint: str | None = None


class CheckpointState(BaseModel):
    """
    Captures serialized state for base optimizer and subclass payload.
    """

    base_state: Dict[str, Any]
    optimizer_state: Dict[str, Any]
