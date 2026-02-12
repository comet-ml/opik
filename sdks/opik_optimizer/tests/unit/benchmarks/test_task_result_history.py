"""Tests for benchmark optimization_history shape validation."""

import pytest

from benchmarks.core.types import TaskResult


def test_task_result_accepts_rounds_history() -> None:
    payload = {
        "id": "task-1",
        "dataset_name": "dataset",
        "optimizer_name": "optimizer",
        "model_name": "model",
        "timestamp_start": 0.0,
        "status": "Success",
        "optimization_history": {"rounds": []},
    }
    result = TaskResult.model_validate(payload)
    assert result.optimization_history["rounds"] == []


def test_task_result_rejects_steps_history() -> None:
    payload = {
        "id": "task-1",
        "dataset_name": "dataset",
        "optimizer_name": "optimizer",
        "model_name": "model",
        "timestamp_start": 0.0,
        "status": "Success",
        "optimization_history": {"steps": []},
    }
    with pytest.raises(ValueError):
        TaskResult.model_validate(payload)
