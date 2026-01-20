"""
Tests for retry behavior in Meta-Prompt candidate generation ops.
"""

from typing import Any

import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.algorithms.meta_prompt_optimizer.meta_prompt_optimizer import (
    MetaPromptOptimizer,
)
from opik_optimizer.algorithms.meta_prompt_optimizer.ops import (
    candidate_single_ops,
    candidate_synthesis_ops,
)
from opik_optimizer.algorithms.meta_prompt_optimizer.ops.halloffame_ops import (
    HallOfFameEntry,
)
from opik_optimizer.algorithms.meta_prompt_optimizer.types import (
    PromptCandidatesResponse,
)
from opik_optimizer.core.llm_calls import StructuredOutputParsingError
from opik_optimizer.core.results import OptimizationRound

pytestmark = pytest.mark.usefixtures("suppress_expected_optimizer_warnings")


def _make_optimizer() -> MetaPromptOptimizer:
    optimizer = MetaPromptOptimizer(verbose=0)
    optimizer.enable_context = False
    return optimizer


def _make_prompt() -> ChatPrompt:
    return ChatPrompt(system="system", user="user")


def _build_prompt_candidate() -> PromptCandidatesResponse:
    return PromptCandidatesResponse(
        prompts=[
            {
                "prompt": [
                    {"role": "system", "content": "s"},
                    {"role": "user", "content": "u"},
                ]
            }
        ]
    )


def test_candidate_single_retries_with_strict_json(mock_llm_call: Any) -> None:
    optimizer = _make_optimizer()
    current_prompt = _make_prompt()

    calls: list[dict[str, Any]] = []

    def side_effect(**kwargs: Any) -> Any:
        calls.append(kwargs)
        if len(calls) == 1:
            raise StructuredOutputParsingError("bad", ValueError("bad"))
        return _build_prompt_candidate()

    mock_llm_call(side_effect=side_effect)

    results = candidate_single_ops.generate_candidate_prompts(
        optimizer=optimizer,
        current_prompt=current_prompt,
        best_score=0.5,
        round_num=0,
        previous_rounds=[],
        metric=lambda item, output: 0.5,
        build_history_context_fn=lambda _: "",
        get_task_context_fn=lambda **_: ("", "", ""),
    )

    assert results
    assert len(calls) == 2
    assert "Return ONLY valid JSON" in calls[1]["messages"][1]["content"]


def test_candidate_single_raises_when_all_rejected(
    monkeypatch: pytest.MonkeyPatch, mock_llm_call: Any
) -> None:
    optimizer = _make_optimizer()
    current_prompt = _make_prompt()

    mock_llm_call(_build_prompt_candidate())

    def reject_all(prompt_json: dict[str, Any], metric_name: str) -> dict[str, Any]:
        _ = metric_name
        return {"prompts": []}

    monkeypatch.setattr(
        candidate_single_ops,
        "sanitize_generated_prompts",
        reject_all,
    )

    with pytest.raises(ValueError, match="No valid prompts found"):
        candidate_single_ops.generate_candidate_prompts(
            optimizer=optimizer,
            current_prompt=current_prompt,
            best_score=0.5,
            round_num=0,
            previous_rounds=[],
            metric=lambda item, output: 0.5,
            build_history_context_fn=lambda _: "",
            get_task_context_fn=lambda **_: ("", "", ""),
        )


def test_candidate_synthesis_retries_with_strict_json(mock_llm_call: Any) -> None:
    optimizer = _make_optimizer()
    current_prompt = _make_prompt()
    assert optimizer.hall_of_fame is not None
    optimizer.hall_of_fame.entries = [
        HallOfFameEntry(
            prompt_messages=current_prompt.get_messages(),
            score=0.8,
            trial_number=1,
            improvement_over_baseline=0.1,
            metric_name="metric",
        )
    ]

    calls: list[dict[str, Any]] = []

    def side_effect(**kwargs: Any) -> Any:
        calls.append(kwargs)
        if len(calls) == 1:
            raise StructuredOutputParsingError("bad", ValueError("bad"))
        return _build_prompt_candidate()

    mock_llm_call(side_effect=side_effect)

    results = candidate_synthesis_ops.generate_synthesis_prompts(
        optimizer=optimizer,
        current_prompt=current_prompt,
        best_score=0.5,
        previous_rounds=[OptimizationRound(round_index=0, trials=[], best_score=0.5)],
        metric=lambda item, output: 0.5,
        get_task_context_fn=lambda **_: ("", ""),
        round_num=0,
    )

    assert results
    assert len(calls) == 2
    assert "Return ONLY valid JSON" in calls[1]["messages"][1]["content"]
