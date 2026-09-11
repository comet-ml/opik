"""Shared helpers for parameter optimizer unit tests."""

from __future__ import annotations

from typing import Any

from opik_optimizer.algorithms.parameter_optimizer.ops.search_ops import (
    ParameterSearchSpace,
    ParameterSpec,
)
from opik_optimizer.algorithms.parameter_optimizer.types import ParameterType


def float_param(
    name: str,
    *,
    low: float = 0.0,
    high: float = 1.0,
    **kwargs: Any,
) -> ParameterSpec:
    return ParameterSpec(
        name=name,
        distribution=ParameterType.FLOAT,
        low=low,
        high=high,
        **kwargs,
    )


def categorical_param(name: str, *, choices: list[str], **kwargs: Any) -> ParameterSpec:
    return ParameterSpec(
        name=name,
        distribution=ParameterType.CATEGORICAL,
        choices=choices,
        **kwargs,
    )


def space(*parameters: ParameterSpec) -> ParameterSearchSpace:
    return ParameterSearchSpace(parameters=list(parameters))
