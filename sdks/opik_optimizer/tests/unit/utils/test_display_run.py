from typing import Any
from unittest.mock import MagicMock

from opik_optimizer.utils.display.run import OptimizationRunDisplay


def test_evaluation_progress_uses_display_utils(
    monkeypatch: Any,
) -> None:
    captured = {}

    def fake_display_evaluation_progress(**kwargs: Any) -> None:
        captured.update(kwargs)

    monkeypatch.setattr(
        "opik_optimizer.utils.display.terminal.display_evaluation_progress",
        fake_display_evaluation_progress,
    )

    display = OptimizationRunDisplay(verbose=1)
    context = MagicMock()
    context.trials_completed = 1
    context.current_best_score = 0.5
    context.evaluation_dataset = MagicMock()
    context.validation_dataset = None

    display.evaluation_progress(
        context=context,
        prompts={"main": MagicMock()},
        score=0.5,
    )

    assert captured.get("prefix") == "Trial 1"


def test_evaluation_progress_handles_non_finite_scores(
    monkeypatch: Any,
) -> None:
    captured = {}

    def fake_display_evaluation_progress(**kwargs: Any) -> None:
        captured.update(kwargs)

    monkeypatch.setattr(
        "opik_optimizer.utils.display.terminal.display_evaluation_progress",
        fake_display_evaluation_progress,
    )

    display = OptimizationRunDisplay(verbose=1)
    context = MagicMock()
    context.trials_completed = 2
    context.current_best_score = float("inf")
    context.evaluation_dataset = MagicMock()
    context.validation_dataset = None

    display.evaluation_progress(
        context=context,
        prompts={"main": MagicMock()},
        score=float("inf"),
    )

    assert captured["style"] == "yellow"
    assert captured["score_text"] == "non-finite score"
