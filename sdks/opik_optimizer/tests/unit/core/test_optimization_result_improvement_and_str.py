"""Tests for OptimizationResult improvement display + __str__ output."""

from __future__ import annotations

from opik_optimizer import ChatPrompt
from opik_optimizer.core.results import OptimizationResult


class TestCalculateImprovementStr:
    """Tests for _calculate_improvement_str method."""

    def test_positive_improvement(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.90,
            metric_name="accuracy",
            initial_score=0.60,
        )
        improvement = result._calculate_improvement_str()
        assert "50.00%" in improvement
        assert "green" in improvement

    def test_negative_improvement(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.40,
            metric_name="accuracy",
            initial_score=0.60,
        )
        improvement = result._calculate_improvement_str()
        assert "-" in improvement
        assert "red" in improvement

    def test_no_initial_score(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
        )
        improvement = result._calculate_improvement_str()
        assert "N/A" in improvement

    def test_zero_initial_score_with_positive_final(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            initial_score=0.0,
        )
        improvement = result._calculate_improvement_str()
        assert "infinite" in improvement

    def test_zero_initial_and_final_scores(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.0,
            metric_name="accuracy",
            initial_score=0.0,
        )
        improvement = result._calculate_improvement_str()
        assert "0.00%" in improvement


class TestOptimizationResultEdgeCases:
    """Tests for edge cases in OptimizationResult."""

    def test_handles_empty_details(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            details={},
        )
        output = str(result)
        assert "OPTIMIZATION COMPLETE" in output

    def test_handles_search_ranges_with_choices(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            details={
                "optimized_parameters": {"model": "gpt-4"},
                "search_ranges": {
                    "stage1": {"model": {"choices": ["gpt-3.5", "gpt-4"]}}
                },
            },
        )
        output = str(result)
        assert "model" in output

    def test_handles_search_stages(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            details={
                "optimized_parameters": {"temperature": 0.5},
                "search_stages": [{"stage": "coarse"}, {"stage": "fine"}],
                "search_ranges": {
                    "coarse": {"temperature": {"min": 0.0, "max": 1.0}},
                    "fine": {"temperature": {"min": 0.3, "max": 0.7}},
                },
            },
        )
        output = str(result)
        assert "temperature" in output
