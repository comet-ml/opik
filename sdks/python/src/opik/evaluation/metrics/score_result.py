import dataclasses
from typing import Optional, Dict, Any


@dataclasses.dataclass
class ScoreResult:
    name: str
    value: float
    reason: Optional[str] = None
    metadata: Optional[Dict[str, Any]] = None
    scoring_failed: bool = False
