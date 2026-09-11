"""Type definitions for GEPA Optimizer."""

from dataclasses import dataclass
from typing import Any


@dataclass
class OpikDataInst:
    """Data instance handed to GEPA.

    We keep the original Opik dataset item so metrics and prompt formatting can use it
    directly without duplicated bookkeeping.
    """

    input_text: str
    answer: str
    additional_context: dict[str, str]
    opik_item: dict[str, Any]
