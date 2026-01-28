import dataclasses
from typing import Any


@dataclasses.dataclass
class ScoreResult:
    name: str
    value: float
    reason: str | None = None
    metadata: dict[str, Any] | None = None
    scoring_failed: bool = False
