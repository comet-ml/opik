"""Pytest fixtures for common dataset items and metric functions."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

import pytest


@pytest.fixture
def sample_dataset_items() -> list[dict[str, Any]]:
    """Standard dataset items for testing evaluations."""
    return [
        {"id": "item-1", "question": "What is 2+2?", "answer": "4"},
        {"id": "item-2", "question": "What is the capital of France?", "answer": "Paris"},
        {"id": "item-3", "question": "What is the largest planet?", "answer": "Jupiter"},
        {"id": "item-4", "question": "Who wrote Romeo and Juliet?", "answer": "Shakespeare"},
        {"id": "item-5", "question": "What is H2O?", "answer": "Water"},
    ]


@pytest.fixture
def large_dataset_items() -> list[dict[str, Any]]:
    """Larger dataset for testing pagination and batching."""
    return [
        {"id": f"item-{i}", "question": f"Question {i}?", "answer": f"Answer {i}"}
        for i in range(50)
    ]


MetricFn = Callable[[dict[str, Any], dict[str, Any]], float]
MetricWithReasonFn = Callable[[dict[str, Any], dict[str, Any]], dict[str, Any]]


@pytest.fixture
def sample_metric() -> MetricFn:
    """Simple accuracy metric used by many tests."""

    def accuracy_metric(dataset_item: dict[str, Any], llm_output: dict[str, Any]) -> float:
        expected = str(dataset_item.get("answer", "")).lower()
        actual = str(llm_output.get("llm_output", "")).lower()
        return 1.0 if expected in actual else 0.0

    accuracy_metric.__name__ = "accuracy_metric"
    return accuracy_metric


@pytest.fixture
def sample_metric_with_reason() -> MetricWithReasonFn:
    """Accuracy metric returning {score, reason} for reason-aware algorithms."""

    def accuracy_with_reason(
        dataset_item: dict[str, Any], llm_output: dict[str, Any]
    ) -> dict[str, Any]:
        expected = str(dataset_item.get("answer", "")).lower()
        actual = str(llm_output.get("llm_output", "")).lower()
        score = 1.0 if expected in actual else 0.0
        reason = (
            f"Expected '{expected}' found in output"
            if score == 1.0
            else f"Expected '{expected}' not found in output '{actual[:50]}...'"
        )
        return {"score": score, "reason": reason}

    accuracy_with_reason.__name__ = "accuracy_with_reason"
    return accuracy_with_reason

