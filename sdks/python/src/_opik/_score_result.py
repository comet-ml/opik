"""Lightweight ScoreResult dataclass with no heavy dependencies."""

import dataclasses
from typing import Any, Dict, Optional


@dataclasses.dataclass
class ScoreResult:
    """Result returned by a metric's ``score`` method.

    Attributes:
        name: Metric name that produced this result.
        value: Numeric score value.
        reason: Optional human-readable explanation.
        category_name: Optional category label.
        metadata: Optional dictionary of extra metadata.
        scoring_failed: Flag indicating the scoring could not complete.
    """

    name: str
    value: float
    reason: Optional[str] = None
    category_name: Optional[str] = None
    metadata: Optional[Dict[str, Any]] = None
    scoring_failed: bool = False
