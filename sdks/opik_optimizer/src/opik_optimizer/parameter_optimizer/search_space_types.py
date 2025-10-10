"""Type definitions for parameter search space."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Literal


class ParameterType(str, Enum):
    """Supported parameter distribution types."""

    FLOAT = "float"
    INT = "int"
    CATEGORICAL = "categorical"
    BOOL = "bool"


@dataclass(frozen=True)
class ResolvedTarget:
    """Resolved target location for a parameter."""

    root: Literal["model", "model_kwargs"]
    path: tuple[str, ...]
