"""Tests for OptimizationResult.model_dump()."""

from __future__ import annotations

from opik_optimizer import ChatPrompt
from opik_optimizer.core.results import OptimizationResult


class TestOptimizationResultModelDump:
    """Tests for model_dump method."""

    def test_model_dump_returns_dict(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
        )
        dumped = result.model_dump()
        assert isinstance(dumped, dict)
        assert dumped["score"] == 0.85
        assert dumped["metric_name"] == "accuracy"

    def test_model_dump_includes_all_fields(self) -> None:
        result = OptimizationResult(
            optimizer="TestOptimizer",
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            optimization_id="opt-123",
            llm_calls=50,
        )
        dumped = result.model_dump()
        assert dumped["optimizer"] == "TestOptimizer"
        assert dumped["optimization_id"] == "opt-123"
        assert dumped["llm_calls"] == 50
