from __future__ import annotations

from __future__ import annotations

from typing import Any

import pytest

from opik.evaluation.metrics import score_result
from scripts.optimizer_algorithms.utils import metrics as metrics_module


def test_answer_correctness_score_requires_answer_field() -> None:
    dataset_item = {"question": "Q1"}
    with pytest.raises(ValueError, match="requires dataset items with an 'answer'"):
        metrics_module.answer_correctness_score(dataset_item, "output")


def test_answer_correctness_score_returns_score_result(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, Any] = {}

    fake_result = score_result.ScoreResult(
        name="answer_correctness",
        value=1.0,
        reason="parsed",
    )

    class FakeMetric:
        def __init__(
            self, *args: Any, **kwargs: Any
        ) -> None:  # pragma: no cover - trivial
            del args, kwargs

        def score(
            self,
            *,
            output: str,
            reference: str,
            **_: Any,
        ) -> score_result.ScoreResult:
            captured["output"] = output
            captured["reference"] = reference
            return fake_result

    monkeypatch.setattr(metrics_module, "AnswerCorrectnessMetric", FakeMetric)

    result = metrics_module.answer_correctness_score(
        {"answer": "golden"},
        "generation",
    )

    assert result is fake_result
    assert captured == {"output": "generation", "reference": "golden"}
    assert getattr(metrics_module.answer_correctness_score, "required_fields") == (
        "answer",
    )
