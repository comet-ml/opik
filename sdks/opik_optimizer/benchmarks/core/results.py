from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class RunSummary:
    engine: str
    run_id: str | None
    status: str
    metadata: dict[str, Any] = field(default_factory=dict)
