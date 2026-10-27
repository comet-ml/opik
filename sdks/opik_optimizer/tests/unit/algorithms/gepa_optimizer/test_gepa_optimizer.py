# mypy: disable-error-code=no-untyped-def

from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import pytest

from opik_optimizer import ChatPrompt, GepaOptimizer
from opik_optimizer.algorithms.gepa_optimizer.adapter import OpikGEPAAdapter
from tests.unit.fixtures import assert_baseline_early_stop
from tests.unit.fixtures import make_baseline_prompt
from tests.unit.test_helpers import (
    make_mock_dataset,
    make_optimization_context,
    STANDARD_DATASET_ITEMS,
)


class TestGepaOptimizerEarlyStop:
    def test_skips_on_perfect_score(
        self,
        mock_opik_client: Callable[..., MagicMock],
        monkeypatch: pytest.MonkeyPatch,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        mock_opik_client()
        dataset = mock_dataset(
            sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
        )
        optimizer = GepaOptimizer(model="gpt-4o", perfect_score=0.95)

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)

        prompt = make_baseline_prompt()
        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=sample_metric,
            max_trials=1,
        )

        assert_baseline_early_stop(result, perfect_score=0.95, trials_completed=1)
        # Early stop happens before run_optimization, so only baseline was evaluated
        # Framework sets trials_completed to 1 (baseline evaluation counts as 1 trial)
        assert result.details["trials_completed"] == 1

    def test_early_stop_reports_at_least_one_trial(
        self,
        mock_opik_client: Callable[..., MagicMock],
        monkeypatch: pytest.MonkeyPatch,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """Verify GepaOptimizer early stop reports at least 1 trial."""
        mock_opik_client()
        dataset = mock_dataset(
            sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
        )
        optimizer = GepaOptimizer(model="gpt-4o", perfect_score=0.95)

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)

        prompt = make_baseline_prompt()
        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=sample_metric,
            max_trials=1,
        )

        assert_baseline_early_stop(
            result, perfect_score=0.95, trials_completed=1, history_len=1
        )


def test_gepa_adapter_records_per_item_metrics() -> None:
    """Ensure per-item adapter scoring does not override round best scores."""
    dataset = make_mock_dataset(
        STANDARD_DATASET_ITEMS[:1], name="test-dataset", dataset_id="dataset-123"
    )
    prompt = make_baseline_prompt()

    def metric_fn(dataset_item: dict[str, Any], llm_output: str) -> float:
        return 0.1

    metric_fn.__name__ = "metric_fn"

    context = make_optimization_context(prompt, dataset=dataset, metric=metric_fn)
    optimizer = GepaOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
    adapter = OpikGEPAAdapter(
        base_prompts=context.prompts,
        agent=MagicMock(),
        optimizer=optimizer,
        context=context,
        metric=metric_fn,
        dataset=dataset,
        experiment_config=None,
    )

    adapter._record_and_post_candidate(
        prompt_variants=context.prompts,
        score=1.0,
        metrics={"adapter_metric": 1.0},
    )

    history = optimizer.get_history_entries()
    assert history
    entry = history[0]
    trials = entry.get("trials") or []
    candidates = entry.get("candidates") or []
    assert trials
    assert candidates
    assert trials[0].get("score") is None
    assert trials[0].get("metrics", {}).get("per_item_score") == 1.0
    assert candidates[0].get("metrics", {}).get("per_item_score") == 1.0
    assert trials[0].get("extra", {}).get("score_label") == "per_item"
    # This confirms that adapter creation in run_optimization
    # can access self.agent successfully


def test_gepa_adapter_passes_allow_tool_use_to_candidate_agent() -> None:
    dataset = make_mock_dataset(
        STANDARD_DATASET_ITEMS[:1], name="test-dataset", dataset_id="dataset-123"
    )
    prompt = make_baseline_prompt()

    def metric_fn(dataset_item: dict[str, Any], llm_output: str) -> float:
        _ = dataset_item, llm_output
        return 1.0

    metric_fn.__name__ = "metric_fn"

    class CandidateAgent:
        def __init__(self) -> None:
            self.last_allow_tool_use: bool | None = None

        def invoke_agent_candidates(
            self,
            prompts: dict[str, ChatPrompt],
            dataset_item: dict[str, Any],
            allow_tool_use: bool = False,
        ) -> list[str]:
            _ = prompts, dataset_item
            self.last_allow_tool_use = allow_tool_use
            return [" candidate "]

    agent = CandidateAgent()
    context = make_optimization_context(
        prompt, dataset=dataset, metric=metric_fn, allow_tool_use=True
    )
    optimizer = GepaOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
    adapter = OpikGEPAAdapter(
        base_prompts=context.prompts,
        agent=agent,  # type: ignore[arg-type]
        optimizer=optimizer,
        context=context,
        metric=metric_fn,
        dataset=dataset,
        experiment_config=None,
    )

    result = adapter._collect_candidates(context.prompts, STANDARD_DATASET_ITEMS[0])

    assert result == ["candidate"]
    assert agent.last_allow_tool_use is True


def test_gepa_adapter_falls_back_for_legacy_agent_signature() -> None:
    dataset = make_mock_dataset(
        STANDARD_DATASET_ITEMS[:1], name="test-dataset", dataset_id="dataset-123"
    )
    prompt = make_baseline_prompt()

    def metric_fn(dataset_item: dict[str, Any], llm_output: str) -> float:
        _ = dataset_item, llm_output
        return 1.0

    metric_fn.__name__ = "metric_fn"

    class LegacyAgent:
        def invoke_agent(
            self,
            prompts: dict[str, ChatPrompt],
            dataset_item: dict[str, Any],
        ) -> str:
            _ = prompts, dataset_item
            return " legacy "

    context = make_optimization_context(
        prompt, dataset=dataset, metric=metric_fn, allow_tool_use=True
    )
    optimizer = GepaOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
    adapter = OpikGEPAAdapter(
        base_prompts=context.prompts,
        agent=LegacyAgent(),  # type: ignore[arg-type]
        optimizer=optimizer,
        context=context,
        metric=metric_fn,
        dataset=dataset,
        experiment_config=None,
    )

    result = adapter._collect_candidates(context.prompts, STANDARD_DATASET_ITEMS[0])
    assert result == ["legacy"]


def test_gepa_adapter_empty_candidates_returns_empty_output() -> None:
    """When the agent produces no candidates, llm_task should return empty output instead of crashing."""
    dataset = make_mock_dataset(
        STANDARD_DATASET_ITEMS[:1], name="test-dataset", dataset_id="dataset-123"
    )
    prompt = make_baseline_prompt()

    def metric_fn(dataset_item: dict[str, Any], llm_output: str) -> float:
        _ = dataset_item, llm_output
        return 0.0

    metric_fn.__name__ = "metric_fn"

    class EmptyAgent:
        def invoke_agent_candidates(
            self,
            prompts: dict[str, ChatPrompt],
            dataset_item: dict[str, Any],
            allow_tool_use: bool = False,
        ) -> list[str]:
            _ = prompts, dataset_item, allow_tool_use
            return []

    context = make_optimization_context(
        prompt, dataset=dataset, metric=metric_fn, allow_tool_use=False
    )
    optimizer = GepaOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
    adapter = OpikGEPAAdapter(
        base_prompts=context.prompts,
        agent=EmptyAgent(),  # type: ignore[arg-type]
        optimizer=optimizer,
        context=context,
        metric=metric_fn,
        dataset=dataset,
        experiment_config=None,
    )

    result = adapter._collect_candidates(context.prompts, STANDARD_DATASET_ITEMS[0])
    assert result == []


def test_gepa_adapter_whitespace_only_candidates_filtered() -> None:
    """When the agent produces only whitespace candidates, they should be filtered out."""
    dataset = make_mock_dataset(
        STANDARD_DATASET_ITEMS[:1], name="test-dataset", dataset_id="dataset-123"
    )
    prompt = make_baseline_prompt()

    def metric_fn(dataset_item: dict[str, Any], llm_output: str) -> float:
        _ = dataset_item, llm_output
        return 0.0

    metric_fn.__name__ = "metric_fn"

    class WhitespaceAgent:
        def invoke_agent_candidates(
            self,
            prompts: dict[str, ChatPrompt],
            dataset_item: dict[str, Any],
            allow_tool_use: bool = False,
        ) -> list[str]:
            _ = prompts, dataset_item, allow_tool_use
            return ["", "   ", "\n"]

    context = make_optimization_context(
        prompt, dataset=dataset, metric=metric_fn, allow_tool_use=False
    )
    optimizer = GepaOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
    adapter = OpikGEPAAdapter(
        base_prompts=context.prompts,
        agent=WhitespaceAgent(),  # type: ignore[arg-type]
        optimizer=optimizer,
        context=context,
        metric=metric_fn,
        dataset=dataset,
        experiment_config=None,
    )

    result = adapter._collect_candidates(context.prompts, STANDARD_DATASET_ITEMS[0])
    assert result == []


@pytest.mark.parametrize(
    "candidates,expected_output,expected_metric_calls",
    [
        # n=1: Opik scores the output itself, so no extra metric call per item.
        (["only"], "only", 0),
        # n>1: pass@k picks the best candidate by metric.
        (["bad", "good"], "good", 2),
    ],
)
def test_gepa_adapter_llm_task_scores_candidates_only_for_pass_at_k(
    monkeypatch: pytest.MonkeyPatch,
    candidates: list[str],
    expected_output: str,
    expected_metric_calls: int,
) -> None:
    from opik_optimizer.algorithms.gepa_optimizer import adapter as adapter_module
    from opik_optimizer.algorithms.gepa_optimizer.types import OpikDataInst

    item = {"id": "t1", "question": "q", "answer": "a"}
    dataset = make_mock_dataset([item], name="test-dataset", dataset_id="ds-1")
    metric_calls: list[str] = []

    def metric_fn(dataset_item: dict[str, Any], llm_output: str) -> float:
        metric_calls.append(llm_output)
        return 1.0 if llm_output == "good" else 0.0

    metric_fn.__name__ = "metric_fn"

    class CandidateAgent:
        def invoke_agent_candidates(self, **_kwargs: Any) -> list[str]:
            return list(candidates)

    context = make_optimization_context(
        make_baseline_prompt(), dataset=dataset, metric=metric_fn
    )
    adapter = OpikGEPAAdapter(
        base_prompts=context.prompts,
        agent=CandidateAgent(),  # type: ignore[arg-type]
        optimizer=GepaOptimizer(model="gpt-4o-mini", verbose=0, seed=42),
        context=context,
        metric=metric_fn,
        dataset=dataset,
        experiment_config=None,
    )
    seen: dict[str, Any] = {}

    def fake_evaluate_with_result(**kwargs: Any) -> tuple[float, Any]:
        seen["output"] = kwargs["evaluated_task"](item)["llm_output"]
        seen["metric_calls"] = len(metric_calls)
        eval_result = MagicMock()
        eval_result.test_results = []
        return 1.0, eval_result

    monkeypatch.setattr(
        adapter_module.task_evaluator, "evaluate_with_result", fake_evaluate_with_result
    )
    batch = [
        OpikDataInst(input_text="q", answer="a", additional_context={}, opik_item=item)
    ]
    adapter.evaluate(batch, candidate={}, capture_traces=False)

    assert seen == {
        "output": expected_output,
        "metric_calls": expected_metric_calls,
    }


@pytest.mark.parametrize(
    "kwargs,match",
    [
        ({"model": None}, "model parameter is required"),
        ({"model": 123}, "model must be a string, got int"),
        ({"model": "   "}, "model cannot be empty"),
        ({"model": "gpt-4o-mini", "verbose": "1"}, "verbose must be an integer"),
        ({"model": "gpt-4o-mini", "verbose": -1}, "verbose must be non-negative"),
        ({"model": "gpt-4o-mini", "seed": "42"}, "seed must be an integer"),
    ],
)
def test_invalid_constructor_args_raise(kwargs: dict, match: str) -> None:
    with pytest.raises(ValueError, match=match):
        GepaOptimizer(**kwargs)
