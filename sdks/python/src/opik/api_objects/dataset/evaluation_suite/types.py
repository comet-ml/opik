"""Type definitions for evaluation suite."""

from __future__ import annotations

from typing import Any, Dict, List, TypedDict

from typing_extensions import Required

from ..execution_policy import ExecutionPolicy
from .evaluation_suite_result import EvaluationSuiteResult, ItemResult

__all__ = ["EvaluationSuiteItem", "EvaluationSuiteResult", "ItemResult"]


class EvaluationSuiteItem(TypedDict, total=False):
    """A test case item to add to an evaluation suite."""

    data: Required[Dict[str, Any]]
    assertions: List[str]
    description: str
    execution_policy: ExecutionPolicy
