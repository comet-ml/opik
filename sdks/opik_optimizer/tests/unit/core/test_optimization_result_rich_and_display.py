"""Tests for OptimizationResult rich rendering + display()."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.core.results import OptimizationResult
from tests.unit.fixtures import system_message, user_message


class TestOptimizationResultRich:
    """Tests for __rich__ method."""

    def test_rich_returns_panel(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
        )
        panel = result.__rich__()
        # Should return a rich Panel object
        assert panel is not None

    def test_rich_with_parameter_summary(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            initial_score=0.60,
            details={
                "optimized_parameters": {"temperature": 0.7, "top_p": 0.9},
                "parameter_importance": {"temperature": 0.5, "top_p": 0.3},
                "search_ranges": {
                    "coarse": {
                        "temperature": {"min": 0.0, "max": 1.0},
                        "top_p": {"min": 0.5, "max": 1.0},
                    }
                },
            },
        )
        panel = result.__rich__()
        assert panel is not None

    def test_rich_with_dict_prompt(self) -> None:
        result = OptimizationResult(
            prompt={
                "main": ChatPrompt(system="Main", user="Query"),
                "helper": ChatPrompt(system="Helper", user="Task"),
            },
            score=0.85,
            metric_name="accuracy",
        )
        panel = result.__rich__()
        assert panel is not None

    def test_handles_multimodal_prompt_in_rich(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(
                messages=[
                    system_message("Analyze."),
                    user_message(
                        [
                            {"type": "text", "text": "What is this?"},
                            {
                                "type": "image_url",
                                "image_url": {"url": "data:image/png;base64,abc"},
                            },
                        ]
                    ),
                ]
            ),
            score=0.85,
            metric_name="accuracy",
        )
        panel = result.__rich__()
        assert panel is not None


class TestOptimizationResultDisplay:
    """Tests for display method."""

    def test_display_prints_output(self, capsys: pytest.CaptureFixture) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
        )
        with patch("opik_optimizer.core.results.get_console") as mock_console:
            mock_console.return_value = MagicMock()
            result.display()
            mock_console.return_value.print.assert_called_once()

    def test_display_shows_link_when_available(
        self, capsys: pytest.CaptureFixture
    ) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            optimization_id="opt-123",
            dataset_id="ds-123",
        )
        with patch("opik_optimizer.core.results.get_console") as mock_console:
            mock_console.return_value = MagicMock()
            result.display()
            from opik_optimizer.utils.reporting import get_optimization_run_url_by_id

            expected_link = get_optimization_run_url_by_id(
                optimization_id="opt-123", dataset_id="ds-123"
            )
            assert "optimization_id=opt-123" in expected_link
            assert "dataset_id=ds-123" in expected_link
            assert "path=" in expected_link
            mock_console.return_value.print.assert_called_once()

    def test_display_shows_no_link_message_when_missing(
        self, capsys: pytest.CaptureFixture
    ) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
        )
        with patch("opik_optimizer.core.results.get_console") as mock_console:
            mock_console.return_value = MagicMock()
            result.display()
            mock_console.return_value.print.assert_called_once()
