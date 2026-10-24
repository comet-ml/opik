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


def test_evaluation_progress_shows_dataset_info_alongside_caller_extras(
    monkeypatch: Any,
) -> None:
    from opik_optimizer import ChatPrompt
    from tests.unit.test_helpers import make_mock_dataset, make_optimization_context

    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        "opik_optimizer.utils.display.terminal.display_evaluation_progress",
        lambda **kwargs: captured.update(kwargs),
    )
    prompt = ChatPrompt(system="s", user="{question}")
    context = make_optimization_context(
        prompt, dataset=make_mock_dataset(name="train-ds"), max_trials=5
    )
    context.dataset_split = "train"

    OptimizationRunDisplay(verbose=1).evaluation_progress(
        context=context,
        prompts=context.prompts,
        score=0.5,
        display_info={"prev_best_score": 0.4},  # what runtime always passes
    )

    assert captured["dataset_name"] == "train-ds"
    assert captured["dataset_type"] == "training"
    assert captured["evaluation_settings"]
    assert context.dataset_split == "train"  # display must not mutate state
