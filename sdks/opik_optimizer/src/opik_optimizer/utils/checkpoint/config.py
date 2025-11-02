"""
Configuration primitives for optimizer checkpointing.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Literal


@dataclass(slots=True)
class CheckpointConfig:
    """
    Controls how and where checkpoints are produced.
    """

    directory: Path = Path("~/.opik_optimizer/checkpoints").expanduser()
    every_n_rounds: int = 1
    max_to_keep: int | None = 5
    on_exception: Literal["save", "skip", "ask"] = "save"
    compression: Literal["gzip", "none"] = "gzip"

    def resolve_directory(self) -> Path:
        """Return the concrete directory path."""
        return self.directory.expanduser().resolve()


DEFAULT_CHECKPOINT_CONFIG: CheckpointConfig = CheckpointConfig()
