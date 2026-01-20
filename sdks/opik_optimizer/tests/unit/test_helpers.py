"""
Backward-compatible re-exports for older unit tests.

New helpers should live in `tests.unit.fixtures.builders`. This module remains
as a thin shim so refactors can be incremental across the test suite.
"""

from __future__ import annotations

from .fixtures.builders import (  # noqa: F401
    STANDARD_DATASET_ITEMS,
    make_candidate_agent,
    make_fake_evaluator,
    make_fake_llm_call,
    make_mock_dataset,
    make_mock_response,
    make_optimization_context,
    make_simple_metric,
)
