# mypy: disable-error-code=no-untyped-def

"""
Unit tests for opik_optimizer.base_optimizer module.

Tests cover:
- _validate_optimization_inputs: Input validation
- _deep_merge_dicts: Dictionary merging
- _serialize_tools: Tool serialization
- _build_agent_config: Config building
- get_optimizer_metadata: Metadata generation
- Counter and history management
"""

from __future__ import annotations

from decimal import Decimal
from typing import Any, TYPE_CHECKING, cast
from unittest.mock import MagicMock

import pytest

from opik_optimizer.base_optimizer import (
    BaseOptimizer,
    OptimizationRound,
    OptimizationContext,
)
from opik_optimizer.constants import MIN_EVAL_THREADS, MAX_EVAL_THREADS
from opik_optimizer.api_objects import chat_prompt

if TYPE_CHECKING:
    from opik import Dataset
    from opik_optimizer.agents import OptimizableAgent
    from opik_optimizer.api_objects import chat_prompt
    from opik_optimizer.api_objects.types import MetricFunction
    from opik_optimizer.optimization_result import OptimizationResult


class ConcreteOptimizer(BaseOptimizer):
    """Concrete implementation for testing the abstract BaseOptimizer."""

    n_threads: int = 12

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self.n_threads = 12

    def optimize_prompt(
        self,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: MetricFunction,
        agent: OptimizableAgent | None = None,
        experiment_config: dict[str, Any] | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
        project_name: str | None = None,
        optimization_id: str | None = None,
        validation_dataset: Dataset | None = None,
        max_trials: int = 10,
        *args: Any,
        **kwargs: Any,
    ) -> OptimizationResult:
        """Required abstract method implementation."""
        return MagicMock()

    def run_optimization(self, context: OptimizationContext) -> OptimizationResult:
        """Required abstract method implementation."""
        return MagicMock()

    def get_config(self, context: OptimizationContext) -> dict[str, Any]:
        """Required abstract method implementation."""
        return {"optimizer": "ConcreteOptimizer"}

    def get_optimizer_metadata(self) -> dict[str, Any]:
        """Return test-specific metadata."""
        return {"test_param": "test_value", "count": 42}


class TestValidateOptimizationInputs:
    """Tests for _validate_optimization_inputs method."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_dataset(self, mock_dataset):
        """Use the mock_dataset fixture from conftest."""
        return mock_dataset([{"id": "1", "question": "Q1", "answer": "A1"}])

    @pytest.fixture
    def mock_metric(self):
        def metric(dataset_item, llm_output):
            return 1.0

        return metric

    def test_accepts_valid_single_prompt(
        self, optimizer, simple_chat_prompt, mock_dataset, mock_metric
    ) -> None:
        """Should accept a valid ChatPrompt, Dataset, and metric."""
        # Patch Dataset check
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)

        # Should not raise
        optimizer._validate_optimization_inputs(
            simple_chat_prompt, mock_ds, mock_metric
        )

    def test_accepts_valid_prompt_dict(
        self, optimizer, simple_chat_prompt, mock_metric
    ) -> None:
        """Should accept a dict of ChatPrompt objects."""
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)
        prompt_dict = {"main": simple_chat_prompt}

        # Should not raise
        optimizer._validate_optimization_inputs(prompt_dict, mock_ds, mock_metric)

    def test_rejects_non_chatprompt(self, optimizer, mock_metric) -> None:
        """Should reject prompt that is not a ChatPrompt."""
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)

        with pytest.raises(ValueError, match="ChatPrompt"):
            optimizer._validate_optimization_inputs(
                "not a prompt", mock_ds, mock_metric
            )

    def test_rejects_dict_with_non_chatprompt_values(
        self, optimizer, mock_metric
    ) -> None:
        """Should reject dict containing non-ChatPrompt values."""
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)
        invalid_dict = {"main": "not a prompt"}

        with pytest.raises(ValueError, match="ChatPrompt"):
            optimizer._validate_optimization_inputs(invalid_dict, mock_ds, mock_metric)

    def test_rejects_non_dataset(
        self, optimizer, simple_chat_prompt, mock_metric
    ) -> None:
        """Should reject non-Dataset object."""
        with pytest.raises(ValueError, match="Dataset"):
            optimizer._validate_optimization_inputs(
                simple_chat_prompt, "not a dataset", mock_metric
            )

    def test_rejects_non_callable_metric(self, optimizer, simple_chat_prompt) -> None:
        """Should reject metric that is not callable."""
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)

        with pytest.raises(ValueError, match="function"):
            optimizer._validate_optimization_inputs(
                simple_chat_prompt, mock_ds, "not a function"
            )

    def test_rejects_multimodal_when_not_supported(
        self, optimizer, multimodal_chat_prompt, mock_metric
    ) -> None:
        """Should reject multimodal prompts when support_content_parts=False."""
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)

        with pytest.raises(ValueError, match="content parts"):
            optimizer._validate_optimization_inputs(
                multimodal_chat_prompt,
                mock_ds,
                mock_metric,
                support_content_parts=False,
            )

    def test_accepts_multimodal_when_supported(
        self, optimizer, multimodal_chat_prompt, mock_metric
    ) -> None:
        """Should accept multimodal prompts when support_content_parts=True."""
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)

        # Should not raise
        optimizer._validate_optimization_inputs(
            multimodal_chat_prompt,
            mock_ds,
            mock_metric,
            support_content_parts=True,
        )


class TestSkipAndResultHelpers:
    """Tests for skip-threshold and result helper utilities."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    def test_should_skip_optimization_respects_defaults(self, optimizer) -> None:
        assert optimizer._should_skip_optimization(0.96) is True
        assert optimizer._should_skip_optimization(0.5) is False

    def test_should_skip_optimization_overrides(self, optimizer) -> None:
        assert optimizer._should_skip_optimization(0.5, perfect_score=0.5) is True
        assert (
            optimizer._should_skip_optimization(0.99, skip_perfect_score=False) is False
        )

    def test_select_result_prompts_single(self, optimizer, simple_chat_prompt) -> None:
        best_prompts = {"main": simple_chat_prompt}
        initial_prompts = {"main": simple_chat_prompt}
        result_prompt, result_initial = optimizer._select_result_prompts(
            best_prompts=best_prompts,
            initial_prompts=initial_prompts,
            is_single_prompt_optimization=True,
        )
        assert result_prompt is simple_chat_prompt
        assert result_initial is simple_chat_prompt

    def test_select_result_prompts_bundle(self, optimizer, simple_chat_prompt) -> None:
        best_prompts = {"main": simple_chat_prompt}
        initial_prompts = {"main": simple_chat_prompt}
        result_prompt, result_initial = optimizer._select_result_prompts(
            best_prompts=best_prompts,
            initial_prompts=initial_prompts,
            is_single_prompt_optimization=False,
        )
        assert result_prompt == best_prompts
        assert result_initial == initial_prompts

    def test_build_early_result_defaults(self, optimizer, simple_chat_prompt) -> None:
        result = optimizer._build_early_result(
            optimizer_name="ConcreteOptimizer",
            prompt=simple_chat_prompt,
            initial_prompt=simple_chat_prompt,
            score=0.75,
            metric_name="metric",
            details={"stopped_early": True},
            dataset_id="dataset-id",
            optimization_id="opt-id",
        )
        assert result.score == 0.75
        assert result.metric_name == "metric"
        assert result.initial_score == 0.75
        assert result.history == []
        assert result.details["stopped_early"] is True


def test_evaluate_prompt_selects_best_candidate(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Choose the highest-scoring candidate when pass@k returns multiple outputs."""
    optimizer = ConcreteOptimizer(model="gpt-4")

    class CandidateAgent(MagicMock):
        def invoke_agent_candidates(
            self, prompts, dataset_item, allow_tool_use=False, seed=None
        ):
            _ = allow_tool_use, seed
            return ["bad", "good"]

        def invoke_agent(self, prompts, dataset_item, allow_tool_use=False, seed=None):
            _ = allow_tool_use, seed
            return "bad"

    agent = CandidateAgent()

    def metric(dataset_item, llm_output):
        _ = dataset_item
        return 1.0 if llm_output == "good" else 0.0

    prompt = chat_prompt.ChatPrompt(
        name="p",
        messages=[{"role": "user", "content": "{input}"}],
        model_parameters={"n": 2},
    )

    def fake_evaluate(
        dataset,
        evaluated_task,
        metric,
        num_threads,
        optimization_id=None,
        dataset_item_ids=None,
        project_name=None,
        n_samples=None,
        experiment_config=None,
        verbose=1,
        return_evaluation_result=False,
    ):
        _ = (
            dataset,
            metric,
            num_threads,
            optimization_id,
            dataset_item_ids,
            project_name,
            n_samples,
            experiment_config,
            verbose,
            return_evaluation_result,
        )
        output = evaluated_task({"id": "1", "input": "x"})
        assert output["llm_output"] == "good"
        return 1.0

    monkeypatch.setattr(
        "opik_optimizer.base_optimizer.task_evaluator.evaluate", fake_evaluate
    )

    dataset = MagicMock()
    dataset.get_items.return_value = [{"id": "1", "input": "x"}]

    score = optimizer.evaluate_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        n_threads=1,
        verbose=0,
    )

    assert score == 1.0


def test_evaluate_prompt_selects_first_candidate(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Respect selection_policy=first when multiple candidates are returned."""
    optimizer = ConcreteOptimizer(model="gpt-4")

    class CandidateAgent(MagicMock):
        def invoke_agent_candidates(
            self, prompts, dataset_item, allow_tool_use=False, seed=None
        ):
            _ = allow_tool_use, seed
            return ["bad", "good"]

        def invoke_agent(self, prompts, dataset_item, allow_tool_use=False, seed=None):
            _ = allow_tool_use, seed
            return "bad"

    agent = CandidateAgent()

    def metric(dataset_item, llm_output):
        _ = dataset_item, llm_output
        return 1.0

    prompt = chat_prompt.ChatPrompt(
        name="p",
        messages=[{"role": "user", "content": "{input}"}],
        model_parameters={"n": 2, "selection_policy": "first"},
    )

    def fake_evaluate(
        dataset,
        evaluated_task,
        metric,
        num_threads,
        optimization_id=None,
        dataset_item_ids=None,
        project_name=None,
        n_samples=None,
        experiment_config=None,
        verbose=1,
        return_evaluation_result=False,
    ):
        _ = (
            dataset,
            metric,
            num_threads,
            optimization_id,
            dataset_item_ids,
            project_name,
            n_samples,
            experiment_config,
            verbose,
            return_evaluation_result,
        )
        output = evaluated_task({"id": "1", "input": "x"})
        assert output["llm_output"] == "bad"
        return 1.0

    monkeypatch.setattr(
        "opik_optimizer.base_optimizer.task_evaluator.evaluate", fake_evaluate
    )

    dataset = MagicMock()
    dataset.get_items.return_value = [{"id": "1", "input": "x"}]

    score = optimizer.evaluate_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        n_threads=1,
        verbose=0,
    )

    assert score == 1.0


def test_evaluate_prompt_concats_candidates(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Respect selection_policy=concat and join multiple candidates."""
    optimizer = ConcreteOptimizer(model="gpt-4")

    class CandidateAgent(MagicMock):
        def invoke_agent_candidates(
            self, prompts, dataset_item, allow_tool_use=False, seed=None
        ):
            _ = allow_tool_use, seed
            return ["bad", "good"]

        def invoke_agent(self, prompts, dataset_item, allow_tool_use=False, seed=None):
            _ = allow_tool_use, seed
            return "bad"

    agent = CandidateAgent()

    def metric(dataset_item, llm_output):
        _ = dataset_item, llm_output
        return 1.0

    prompt = chat_prompt.ChatPrompt(
        name="p",
        messages=[{"role": "user", "content": "{input}"}],
        model_parameters={"n": 2, "selection_policy": "concat"},
    )

    def fake_evaluate(
        dataset,
        evaluated_task,
        metric,
        num_threads,
        optimization_id=None,
        dataset_item_ids=None,
        project_name=None,
        n_samples=None,
        experiment_config=None,
        verbose=1,
        return_evaluation_result=False,
    ):
        _ = (
            dataset,
            metric,
            num_threads,
            optimization_id,
            dataset_item_ids,
            project_name,
            n_samples,
            experiment_config,
            verbose,
            return_evaluation_result,
        )
        output = evaluated_task({"id": "1", "input": "x"})
        assert output["llm_output"] == "bad\n\ngood"
        return 1.0

    monkeypatch.setattr(
        "opik_optimizer.base_optimizer.task_evaluator.evaluate", fake_evaluate
    )

    dataset = MagicMock()
    dataset.get_items.return_value = [{"id": "1", "input": "x"}]

    score = optimizer.evaluate_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        n_threads=1,
        verbose=0,
    )

    assert score == 1.0


def test_evaluate_prompt_selects_max_logprob_candidate(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Prefer the candidate with the highest logprob when configured."""
    optimizer = ConcreteOptimizer(model="gpt-4")

    class CandidateAgent(MagicMock):
        def invoke_agent_candidates(
            self, prompts, dataset_item, allow_tool_use=False, seed=None
        ):
            _ = allow_tool_use, seed
            self._last_candidate_logprobs = [0.2, 0.9]
            return ["bad", "good"]

        def invoke_agent(self, prompts, dataset_item, allow_tool_use=False, seed=None):
            _ = allow_tool_use, seed
            return "bad"

    agent = CandidateAgent()

    def metric(dataset_item, llm_output):
        _ = dataset_item, llm_output
        return 1.0

    prompt = chat_prompt.ChatPrompt(
        name="p",
        messages=[{"role": "user", "content": "{input}"}],
        model_parameters={"n": 2, "selection_policy": "max_logprob"},
    )

    def fake_evaluate(
        dataset,
        evaluated_task,
        metric,
        num_threads,
        optimization_id=None,
        dataset_item_ids=None,
        project_name=None,
        n_samples=None,
        experiment_config=None,
        verbose=1,
        return_evaluation_result=False,
    ):
        _ = (
            dataset,
            metric,
            num_threads,
            optimization_id,
            dataset_item_ids,
            project_name,
            n_samples,
            experiment_config,
            verbose,
            return_evaluation_result,
        )
        output = evaluated_task({"id": "1", "input": "x"})
        assert output["llm_output"] == "good"
        return 1.0

    monkeypatch.setattr(
        "opik_optimizer.base_optimizer.task_evaluator.evaluate", fake_evaluate
    )

    dataset = MagicMock()
    dataset.get_items.return_value = [{"id": "1", "input": "x"}]

    score = optimizer.evaluate_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        n_threads=1,
        verbose=0,
    )

    assert score == 1.0


def test_evaluate_forwards_configured_n_threads(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    """Candidate evaluations should honor the optimizer's configured n_threads."""
    optimizer = ConcreteOptimizer(model="gpt-4", verbose=0)
    optimizer.n_threads = 1

    dataset = MagicMock()
    metric = MagicMock()
    agent = MagicMock()

    optimizer._context = OptimizationContext(
        prompts={"main": simple_chat_prompt},
        initial_prompts={"main": simple_chat_prompt},
        is_single_prompt_optimization=True,
        dataset=dataset,
        evaluation_dataset=dataset,
        validation_dataset=None,
        metric=metric,
        agent=agent,
        optimization=None,
        optimization_id=None,
        experiment_config=None,
        n_samples=None,
        max_trials=3,
        project_name="Test",
    )

    captured_call: dict[str, Any] = {}

    def fake_evaluate_prompt(
        self,
        *,
        prompt,
        dataset,
        metric,
        agent,
        experiment_config,
        n_samples,
        verbose,
        n_threads=None,
        **kwargs,
    ):
        captured_call["n_threads"] = n_threads
        return 0.5

    monkeypatch.setattr(ConcreteOptimizer, "evaluate_prompt", fake_evaluate_prompt)

    score = optimizer.evaluate({"main": simple_chat_prompt})

    assert score == 0.5
    assert captured_call["n_threads"] == optimizer.n_threads


def test_normalize_n_threads_clamps_bounds(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    """Thread counts are clamped to safe bounds before evaluator calls."""
    optimizer = ConcreteOptimizer(model="gpt-4", verbose=0)
    dataset = MagicMock()
    metric = MagicMock(__name__="metric")
    agent = MagicMock()

    dataset.get_items.return_value = [{"id": "1", "input": "x"}]

    captured: list[int] = []

    def fake_evaluate(
        dataset,
        evaluated_task,
        metric,
        num_threads,
        optimization_id=None,
        dataset_item_ids=None,
        project_name=None,
        n_samples=None,
        experiment_config=None,
        verbose=1,
        return_evaluation_result=False,
    ):
        captured.append(num_threads)
        return 1.0

    monkeypatch.setattr(
        "opik_optimizer.base_optimizer.task_evaluator.evaluate", fake_evaluate
    )

    # Below minimum should clamp to MIN_EVAL_THREADS
    optimizer.evaluate_prompt(
        prompt=simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        n_threads=0,
        verbose=0,
    )

    # Above maximum should clamp to MAX_EVAL_THREADS
    optimizer.evaluate_prompt(
        prompt=simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        n_threads=10_000,
        verbose=0,
    )

    assert captured[0] == MIN_EVAL_THREADS
    assert captured[1] == MAX_EVAL_THREADS


def test_evaluate_coerces_infinite_scores(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    """Metrics returning non-builtin numerics (e.g., Decimal('Infinity')) are coerced safely."""
    optimizer = ConcreteOptimizer(model="gpt-4", verbose=0)
    dataset = MagicMock()
    metric = MagicMock()
    agent = MagicMock()

    optimizer._context = OptimizationContext(
        prompts={"main": simple_chat_prompt},
        initial_prompts={"main": simple_chat_prompt},
        is_single_prompt_optimization=True,
        dataset=dataset,
        evaluation_dataset=dataset,
        validation_dataset=None,
        metric=metric,
        agent=agent,
        optimization=None,
        optimization_id=None,
        experiment_config=None,
        n_samples=None,
        max_trials=3,
        project_name="Test",
        baseline_score=None,
        current_best_score=float("inf"),
    )

    def fake_evaluate_prompt(
        self,
        *,
        prompt,
        dataset,
        metric,
        agent,
        experiment_config,
        n_samples,
        n_threads,
        verbose,
        **kwargs,
    ):
        return Decimal("Infinity")

    monkeypatch.setattr(ConcreteOptimizer, "evaluate_prompt", fake_evaluate_prompt)

    score = optimizer.evaluate({"main": simple_chat_prompt})

    assert score == float("inf")
    assert optimizer._context.current_best_score == float("inf")
    assert optimizer._context.trials_completed == 1


def test_coerce_score_rejects_nan() -> None:
    """NaN scores should raise a ValueError."""
    with pytest.raises(ValueError, match="Score cannot be NaN"):
        BaseOptimizer._coerce_score(float("nan"))


def test_coerce_score_rejects_non_numeric() -> None:
    """Non-numeric scores should raise a TypeError."""
    with pytest.raises(TypeError, match="Score must be convertible to float"):
        BaseOptimizer._coerce_score(object())


def test_reporter_helpers_set_and_clear() -> None:
    optimizer = ConcreteOptimizer(model="gpt-4")
    reporter = object()
    optimizer._set_reporter(reporter)
    assert optimizer._reporter is reporter
    optimizer._clear_reporter()
    assert optimizer._reporter is None


def test_on_evaluation_handles_non_finite_scores(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    """_on_evaluation should not crash when scores are non-finite."""
    optimizer = ConcreteOptimizer(model="gpt-4", verbose=1)
    dataset = MagicMock()
    metric = MagicMock()
    agent = MagicMock()

    context = OptimizationContext(
        prompts={"main": simple_chat_prompt},
        initial_prompts={"main": simple_chat_prompt},
        is_single_prompt_optimization=True,
        dataset=dataset,
        evaluation_dataset=dataset,
        validation_dataset=None,
        metric=metric,
        agent=agent,
        optimization=None,
        optimization_id=None,
        experiment_config=None,
        n_samples=None,
        max_trials=5,
        project_name="Test",
        baseline_score=None,
    )
    context.current_best_score = float("inf")
    context.trials_completed = 1
    optimizer._context = context

    captured: dict[str, Any] = {}

    def fake_display_evaluation_progress(**kwargs):
        captured.update(kwargs)

    monkeypatch.setattr(
        "opik_optimizer.reporting_utils.display_evaluation_progress",
        fake_display_evaluation_progress,
    )

    optimizer._on_evaluation(context, {"main": simple_chat_prompt}, float("inf"))

    assert captured["style"] == "yellow"
    assert captured["score_text"] == "non-finite score"


def test_should_stop_context_on_perfect_score(simple_chat_prompt) -> None:
    optimizer = ConcreteOptimizer(
        model="gpt-4", perfect_score=0.8, skip_perfect_score=True
    )
    dataset = MagicMock()
    metric = MagicMock()
    agent = MagicMock()
    context = OptimizationContext(
        prompts={"main": simple_chat_prompt},
        initial_prompts={"main": simple_chat_prompt},
        is_single_prompt_optimization=True,
        dataset=dataset,
        evaluation_dataset=dataset,
        validation_dataset=None,
        metric=metric,
        agent=agent,
        optimization=None,
        optimization_id=None,
        experiment_config=None,
        n_samples=None,
        max_trials=5,
        project_name="Test",
        baseline_score=None,
    )
    optimizer._context = context

    def fake_eval(*args, **kwargs):
        return 0.9

    optimizer.evaluate_prompt = fake_eval  # type: ignore[assignment]

    optimizer.evaluate({"main": simple_chat_prompt})
    assert context.should_stop is True
    assert context.finish_reason == "perfect_score"


def test_evaluate_sets_finish_reason_on_max_trials(simple_chat_prompt) -> None:
    optimizer = ConcreteOptimizer(
        model="gpt-4", perfect_score=1.5, skip_perfect_score=True
    )
    dataset = MagicMock()
    metric = MagicMock()
    agent = MagicMock()
    context = OptimizationContext(
        prompts={"main": simple_chat_prompt},
        initial_prompts={"main": simple_chat_prompt},
        is_single_prompt_optimization=True,
        dataset=dataset,
        evaluation_dataset=dataset,
        validation_dataset=None,
        metric=metric,
        agent=agent,
        optimization=None,
        optimization_id=None,
        experiment_config=None,
        n_samples=None,
        max_trials=1,
        project_name="Test",
        baseline_score=None,
    )
    optimizer._context = context

    def fake_eval(*args, **kwargs):
        return 0.1

    optimizer.evaluate_prompt = fake_eval  # type: ignore[assignment]

    optimizer.evaluate({"main": simple_chat_prompt})
    assert context.should_stop is True
    assert context.finish_reason == "max_trials"
    assert context.trials_completed == 1


class TestDeepMergeDicts:
    """Tests for _deep_merge_dicts static method."""

    def test_merges_flat_dicts(self) -> None:
        """Should merge two flat dictionaries."""
        base = {"a": 1, "b": 2}
        overrides = {"b": 3, "c": 4}

        result = BaseOptimizer._deep_merge_dicts(base, overrides)

        assert result == {"a": 1, "b": 3, "c": 4}

    def test_deep_merges_nested_dicts(self) -> None:
        """Should recursively merge nested dictionaries."""
        base = {"level1": {"a": 1, "b": 2}, "other": "value"}
        overrides = {"level1": {"b": 3, "c": 4}}

        result = BaseOptimizer._deep_merge_dicts(base, overrides)

        assert result == {"level1": {"a": 1, "b": 3, "c": 4}, "other": "value"}

    def test_override_replaces_non_dict_with_dict(self) -> None:
        """Should replace non-dict value with dict value."""
        base = {"key": "string_value"}
        overrides = {"key": {"nested": "value"}}

        result = BaseOptimizer._deep_merge_dicts(base, overrides)

        assert result == {"key": {"nested": "value"}}

    def test_override_replaces_dict_with_non_dict(self) -> None:
        """Should replace dict value with non-dict value."""
        base = {"key": {"nested": "value"}}
        overrides = {"key": "string_value"}

        result = BaseOptimizer._deep_merge_dicts(base, overrides)

        assert result == {"key": "string_value"}

    def test_does_not_modify_original_dicts(self) -> None:
        """Should not modify the input dictionaries."""
        base = {"a": {"b": 1}}
        overrides = {"a": {"c": 2}}

        BaseOptimizer._deep_merge_dicts(base, overrides)

        assert base == {"a": {"b": 1}}
        assert overrides == {"a": {"c": 2}}

    def test_handles_empty_base(self) -> None:
        """Should handle empty base dictionary."""
        result = BaseOptimizer._deep_merge_dicts({}, {"a": 1})
        assert result == {"a": 1}

    def test_handles_empty_overrides(self) -> None:
        """Should handle empty overrides dictionary."""
        result = BaseOptimizer._deep_merge_dicts({"a": 1}, {})
        assert result == {"a": 1}


class TestSerializeTools:
    """Tests for _serialize_tools static method."""

    def test_serializes_tools_list(self, chat_prompt_with_tools) -> None:
        """Should return deep copy of tools list."""
        result = BaseOptimizer._serialize_tools(chat_prompt_with_tools)

        assert isinstance(result, list)
        assert len(result) == 2
        assert result[0]["function"]["name"] == "search"

    def test_returns_empty_list_when_no_tools(self, simple_chat_prompt) -> None:
        """Should return empty list when prompt has no tools."""
        result = BaseOptimizer._serialize_tools(simple_chat_prompt)

        assert result == []

    def test_returns_deep_copy(self, chat_prompt_with_tools) -> None:
        """Should return a deep copy, not reference original."""
        result = BaseOptimizer._serialize_tools(chat_prompt_with_tools)

        # Modify the result
        result[0]["function"]["name"] = "modified"

        # Original should be unchanged
        assert chat_prompt_with_tools.tools[0]["function"]["name"] == "search"


class TestBuildAgentConfig:
    """Tests for _build_agent_config method."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    def test_includes_prompt_dict(self, optimizer, simple_chat_prompt) -> None:
        """Should include prompt content from to_dict()."""
        config = optimizer._build_agent_config(simple_chat_prompt)

        # Config should include prompt content - simple_chat_prompt has system and user
        # Verify at least one content key exists and has valid content
        has_content = False
        if "system" in config:
            assert isinstance(config["system"], str)
            assert len(config["system"]) > 0
            has_content = True
        if "user" in config:
            assert isinstance(config["user"], str)
            assert len(config["user"]) > 0
            has_content = True
        if "messages" in config:
            assert isinstance(config["messages"], list)
            assert len(config["messages"]) > 0
            has_content = True

        assert has_content, (
            "Config should include prompt content (system, user, or messages)"
        )

    def test_includes_model(self, optimizer, simple_chat_prompt) -> None:
        """Should include model name."""
        config = optimizer._build_agent_config(simple_chat_prompt)

        assert "model" in config

    def test_includes_optimizer_name(self, optimizer, simple_chat_prompt) -> None:
        """Should include optimizer class name."""
        config = optimizer._build_agent_config(simple_chat_prompt)

        assert config["optimizer"] == "ConcreteOptimizer"

    def test_includes_tools(self, optimizer, chat_prompt_with_tools) -> None:
        """Should include serialized tools."""
        config = optimizer._build_agent_config(chat_prompt_with_tools)

        assert "tools" in config
        assert len(config["tools"]) == 2


class TestGetOptimizerMetadata:
    """Tests for get_optimizer_metadata and _build_optimizer_metadata."""

    def test_subclass_metadata_is_included(self) -> None:
        """Subclass metadata should be included via get_optimizer_metadata."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        metadata = optimizer._build_optimizer_metadata()

        assert "parameters" in metadata
        assert metadata["parameters"]["test_param"] == "test_value"
        assert metadata["parameters"]["count"] == 42

    def test_includes_base_metadata(self) -> None:
        """Should include base optimizer metadata."""
        optimizer = ConcreteOptimizer(
            model="gpt-4",
            seed=123,
            model_parameters={"temperature": 0.5},
        )

        metadata = optimizer._build_optimizer_metadata()

        assert metadata["name"] == "ConcreteOptimizer"
        assert metadata["model"] == "gpt-4"
        assert metadata["seed"] == 123
        assert metadata["model_parameters"] == {"temperature": 0.5}

    def test_includes_version(self) -> None:
        """Should include optimizer version."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        metadata = optimizer._build_optimizer_metadata()

        assert "version" in metadata


class TestBuildOptimizationMetadata:
    """Tests for _build_optimization_metadata method."""

    def test_includes_optimizer_name(self) -> None:
        """Should include optimizer class name."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        metadata = optimizer._build_optimization_metadata()

        assert metadata["optimizer"] == "ConcreteOptimizer"

    def test_includes_custom_name_when_set(self) -> None:
        """Should include custom name when provided."""
        optimizer = ConcreteOptimizer(model="gpt-4", name="my-optimization")

        metadata = optimizer._build_optimization_metadata()

        assert metadata["name"] == "my-optimization"

    def test_includes_agent_class_when_provided(self) -> None:
        """Should include agent class name when provided."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        from opik_optimizer.agents import OptimizableAgent

        class CustomAgent(OptimizableAgent):
            def invoke_agent(
                self,
                prompts: dict[str, chat_prompt.ChatPrompt],
                dataset_item: dict[str, Any],
                allow_tool_use: bool = False,
                seed: int | None = None,
            ) -> str:
                return "output"

        metadata = optimizer._build_optimization_metadata(agent_class=CustomAgent)

        assert metadata["agent_class"] == "CustomAgent"


class TestCounterManagement:
    """Tests for counter management methods."""

    def test_counters_start_at_zero(self) -> None:
        """Counters should start at zero."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0

    def test_increment_llm_counter(self) -> None:
        """_increment_llm_counter should increment the counter."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        optimizer._increment_llm_counter()
        optimizer._increment_llm_counter()

        assert optimizer.llm_call_counter == 2

    def test_increment_tool_counter(self) -> None:
        """_increment_tool_counter should increment the counter."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        optimizer._increment_tool_counter()

        assert optimizer.tool_call_counter == 1

    def test_reset_counters(self) -> None:
        """_reset_counters should reset both counters to zero."""
        optimizer = ConcreteOptimizer(model="gpt-4")
        optimizer._increment_llm_counter()
        optimizer._increment_tool_counter()

        optimizer._reset_counters()

        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0


class TestHistoryManagement:
    """Tests for history management methods."""

    def test_history_starts_empty(self) -> None:
        """History should start empty."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        assert optimizer.get_history() == []

    def test_add_to_history(self, simple_chat_prompt) -> None:
        """_add_to_history should add round data."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        round_data = OptimizationRound(
            round_number=1,
            current_prompt=simple_chat_prompt,
            current_score=0.5,
            generated_prompts=[],
            best_prompt=simple_chat_prompt,
            best_score=0.5,
            improvement=0.0,
        )

        optimizer._add_to_history(round_data)

        history = optimizer.get_history()
        assert len(history) == 1
        assert history[0].round_number == 1

    def test_cleanup_clears_history(self, simple_chat_prompt) -> None:
        """cleanup should clear the history."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        round_data = OptimizationRound(
            round_number=1,
            current_prompt=simple_chat_prompt,
            current_score=0.5,
            generated_prompts=[],
            best_prompt=simple_chat_prompt,
            best_score=0.5,
            improvement=0.0,
        )
        optimizer._add_to_history(round_data)

        optimizer.cleanup()

        assert optimizer.get_history() == []


class TestCleanup:
    """Tests for cleanup method."""

    def test_cleanup_resets_counters(self) -> None:
        """cleanup should reset call counters."""
        optimizer = ConcreteOptimizer(model="gpt-4")
        optimizer._increment_llm_counter()
        optimizer._increment_tool_counter()

        optimizer.cleanup()

        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0

    def test_cleanup_clears_opik_client(self) -> None:
        """cleanup should clear the Opik client reference."""
        optimizer = ConcreteOptimizer(model="gpt-4")
        optimizer._opik_client = cast(Any, MagicMock())

        optimizer.cleanup()

        assert optimizer._opik_client is None


class TestOptimizerInitialization:
    """Tests for optimizer initialization."""

    def test_default_values(self) -> None:
        """Should set default values correctly."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        assert optimizer.model == "gpt-4"
        assert optimizer.verbose == 1
        assert optimizer.seed == 42
        assert optimizer.model_parameters == {}
        assert optimizer.name is None
        assert optimizer.project_name == "Optimization"

    def test_custom_values(self) -> None:
        """Should accept custom values."""
        optimizer = ConcreteOptimizer(
            model="claude-3",
            verbose=0,
            seed=123,
            model_parameters={"temperature": 0.7},
            name="my-optimizer",
        )

        assert optimizer.model == "claude-3"
        assert optimizer.verbose == 0
        assert optimizer.seed == 123
        assert optimizer.model_parameters == {"temperature": 0.7}
        assert optimizer.name == "my-optimizer"

    def test_reasoning_model_set_to_model(self) -> None:
        """reasoning_model should be set to the same value as model."""
        optimizer = ConcreteOptimizer(model="gpt-4o")

        assert optimizer.reasoning_model == "gpt-4o"


class TestDescribeAnnotation:
    """Tests for _describe_annotation static method."""

    def test_returns_none_for_empty_annotation(self) -> None:
        """Should return None for inspect._empty."""
        import inspect

        result = BaseOptimizer._describe_annotation(inspect._empty)

        assert result is None

    def test_returns_name_for_type(self) -> None:
        """Should return __name__ for type objects."""
        result = BaseOptimizer._describe_annotation(str)

        assert result == "str"

    def test_returns_string_for_other(self) -> None:
        """Should return string representation for other objects."""
        result = BaseOptimizer._describe_annotation("custom_annotation")

        assert result == "custom_annotation"


class TestNormalizePromptInput:
    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    def test_single_prompt_returns_dict_and_true(
        self, optimizer, simple_chat_prompt
    ) -> None:
        prompts, is_single = optimizer._normalize_prompt_input(simple_chat_prompt)

        assert isinstance(prompts, dict)
        assert len(prompts) == 1
        assert simple_chat_prompt.name in prompts
        assert is_single is True

    def test_dict_prompt_returns_same_dict_and_false(
        self, optimizer, simple_chat_prompt
    ) -> None:
        input_dict = {"main": simple_chat_prompt}

        prompts, is_single = optimizer._normalize_prompt_input(input_dict)

        assert prompts is input_dict
        assert is_single is False


class TestCreateOptimizationRun:
    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    def test_creates_optimization_and_sets_id(
        self, optimizer, mock_opik_client
    ) -> None:
        mock_client = mock_opik_client()
        mock_client.create_optimization.return_value = MagicMock(id="opt-123")

        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)
        mock_ds.name = "test-dataset"

        def metric(dataset_item, llm_output):
            return 1.0

        result = optimizer._create_optimization_run(mock_ds, metric)

        assert result is not None
        assert optimizer.current_optimization_id == "opt-123"

    def test_returns_none_on_error(self, optimizer, mock_opik_client) -> None:
        mock_client = mock_opik_client()
        mock_client.create_optimization.side_effect = Exception("API error")

        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)
        mock_ds.name = "test-dataset"

        def metric(dataset_item, llm_output):
            return 1.0

        result = optimizer._create_optimization_run(mock_ds, metric)

        assert result is None
        assert optimizer.current_optimization_id is None


class TestSelectEvaluationDataset:
    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    def test_returns_training_dataset_when_no_validation(self, optimizer) -> None:
        from opik import Dataset

        training_ds = MagicMock(spec=Dataset)
        training_ds.name = "training"

        result = optimizer._select_evaluation_dataset(training_ds, None)

        assert result is training_ds

    def test_returns_validation_dataset_when_provided(self, optimizer) -> None:
        from opik import Dataset

        training_ds = MagicMock(spec=Dataset)
        training_ds.name = "training"
        validation_ds = MagicMock(spec=Dataset)
        validation_ds.name = "validation"

        result = optimizer._select_evaluation_dataset(training_ds, validation_ds)

        assert result is validation_ds

    def test_returns_training_when_warn_unsupported_set(self, optimizer) -> None:
        """When warn_unsupported=True, validation_dataset is ignored and training is returned."""
        from opik import Dataset

        training_ds = MagicMock(spec=Dataset)
        training_ds.name = "training"
        validation_ds = MagicMock(spec=Dataset)
        validation_ds.name = "validation"

        result = optimizer._select_evaluation_dataset(
            training_ds, validation_ds, warn_unsupported=True
        )

        # When warn_unsupported=True, the warning says validation is ignored,
        # so training dataset should be returned
        assert result is training_ds


class TestSetupOptimization:
    """Tests for _setup_optimization method."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_metric(self):
        def metric(dataset_item, llm_output):
            return 1.0

        metric.__name__ = "test_metric"
        return metric

    def test_returns_optimization_context(
        self,
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """_setup_optimization should return an OptimizationContext."""
        mock_opik_client()
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)
        mock_ds.name = "test-dataset"
        mock_ds.id = "ds-123"
        mock_ds.get_items.return_value = [{"id": "1", "input": "test"}]

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
        )

        assert isinstance(context, OptimizationContext)
        assert context.dataset is mock_ds
        assert context.metric is mock_metric
        assert context.is_single_prompt_optimization is True

    def test_normalizes_single_prompt_to_dict(
        self,
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Single ChatPrompt should be normalized to dict."""
        mock_opik_client()
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)
        mock_ds.name = "test-dataset"
        mock_ds.id = "ds-123"
        mock_ds.get_items.return_value = [{"id": "1", "input": "test"}]

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
        )

        assert isinstance(context.prompts, dict)
        assert simple_chat_prompt.name in context.prompts
        assert context.is_single_prompt_optimization is True

    def test_preserves_dict_prompt(
        self,
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Dict of prompts should be preserved."""
        mock_opik_client()
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)
        mock_ds.name = "test-dataset"
        mock_ds.id = "ds-123"
        mock_ds.get_items.return_value = [{"id": "1", "input": "test"}]

        prompts = {"main": simple_chat_prompt}
        context = optimizer._setup_optimization(
            prompt=prompts,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
        )

        assert context.prompts is prompts
        assert context.is_single_prompt_optimization is False

    def test_creates_agent_if_not_provided(
        self,
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Should create LiteLLMAgent if no agent provided."""
        mock_opik_client()
        from opik import Dataset
        from opik_optimizer.agents import LiteLLMAgent

        mock_ds = MagicMock(spec=Dataset)
        mock_ds.name = "test-dataset"
        mock_ds.id = "ds-123"
        mock_ds.get_items.return_value = [{"id": "1", "input": "test"}]

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            agent=None,
            compute_baseline=False,
        )

        assert isinstance(context.agent, LiteLLMAgent)

    def test_uses_provided_agent(
        self,
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Should use provided agent."""
        mock_opik_client()
        from opik import Dataset
        from opik_optimizer.agents import OptimizableAgent

        mock_ds = MagicMock(spec=Dataset)
        mock_ds.name = "test-dataset"
        mock_ds.id = "ds-123"
        mock_ds.get_items.return_value = [{"id": "1", "input": "test"}]

        mock_agent = MagicMock(spec=OptimizableAgent)

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            agent=mock_agent,
            compute_baseline=False,
        )

        assert context.agent is mock_agent

    def test_stores_extra_params(
        self,
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Extra kwargs should be stored in context.extra_params."""
        mock_opik_client()
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)
        mock_ds.name = "test-dataset"
        mock_ds.id = "ds-123"
        mock_ds.get_items.return_value = [{"id": "1", "input": "test"}]

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            custom_param="custom_value",
            another_param=42,
        )

        assert context.extra_params["custom_param"] == "custom_value"
        assert context.extra_params["another_param"] == 42


class TestFinalizeOptimization:
    """Tests for _finalize_optimization method."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    def test_updates_optimization_status(self, optimizer) -> None:
        """Should update optimization status when optimization exists."""
        mock_optimization = MagicMock()
        context = OptimizationContext(
            prompts={},
            initial_prompts={},
            is_single_prompt_optimization=True,
            dataset=MagicMock(),
            evaluation_dataset=MagicMock(),
            validation_dataset=None,
            metric=MagicMock(),
            agent=MagicMock(),
            optimization=mock_optimization,
            optimization_id="opt-123",
            experiment_config=None,
            n_samples=None,
            max_trials=10,
            project_name="Test",
        )

        optimizer._finalize_optimization(context, status="completed")

        mock_optimization.update.assert_called_with(status="completed")

    def test_handles_none_optimization(self, optimizer) -> None:
        """Should not raise when optimization is None."""
        context = OptimizationContext(
            prompts={},
            initial_prompts={},
            is_single_prompt_optimization=True,
            dataset=MagicMock(),
            evaluation_dataset=MagicMock(),
            validation_dataset=None,
            metric=MagicMock(),
            agent=MagicMock(),
            optimization=None,
            optimization_id=None,
            experiment_config=None,
            n_samples=None,
            max_trials=10,
            project_name="Test",
        )

        # Should not raise
        optimizer._finalize_optimization(context, status="completed")


class TestFinalizeFinishReason:
    """Tests for _finalize_finish_reason method.

    This method sets finish_reason after optimization completes, ensuring
    correct reporting of whether optimization hit max_trials limit or
    completed normally.

    Bug this catches: Previously, when a while loop exited because
    trials_completed >= max_trials (loop condition false), the finish_reason
    was incorrectly set to "completed" instead of "max_trials" because the
    max_trials check was inside the loop and didn't run after loop exit.
    """

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def base_context(self) -> OptimizationContext:
        """Create a minimal context for testing."""
        return OptimizationContext(
            prompts={},
            initial_prompts={},
            is_single_prompt_optimization=True,
            dataset=MagicMock(),
            evaluation_dataset=MagicMock(),
            validation_dataset=None,
            metric=MagicMock(),
            agent=MagicMock(),
            optimization=None,
            optimization_id=None,
            experiment_config=None,
            n_samples=None,
            max_trials=10,
            project_name="Test",
        )

    def test_sets_max_trials_when_limit_reached(self, optimizer, base_context) -> None:
        """finish_reason should be 'max_trials' when trials_completed >= max_trials.

        This is the key test that would fail with the old implementation.
        The bug was: when a while loop exits because trials_completed >= max_trials,
        the code after the loop would unconditionally set finish_reason = "completed"
        instead of checking if max_trials was reached.
        """
        base_context.trials_completed = 10  # Equal to max_trials
        base_context.finish_reason = None  # Not set by early stop

        optimizer._finalize_finish_reason(base_context)

        assert base_context.finish_reason == "max_trials"

    def test_sets_max_trials_when_over_limit(self, optimizer, base_context) -> None:
        """finish_reason should be 'max_trials' when trials_completed > max_trials."""
        base_context.trials_completed = 15  # Over max_trials (10)
        base_context.finish_reason = None

        optimizer._finalize_finish_reason(base_context)

        assert base_context.finish_reason == "max_trials"

    def test_sets_completed_when_under_limit(self, optimizer, base_context) -> None:
        """finish_reason should be 'completed' when trials_completed < max_trials."""
        base_context.trials_completed = 5  # Under max_trials (10)
        base_context.finish_reason = None

        optimizer._finalize_finish_reason(base_context)

        assert base_context.finish_reason == "completed"

    def test_preserves_existing_finish_reason(self, optimizer, base_context) -> None:
        """Should not override finish_reason if already set (e.g., by early stop)."""
        base_context.trials_completed = 10  # At limit
        base_context.finish_reason = "perfect_score"  # Set by early stop

        optimizer._finalize_finish_reason(base_context)

        # Should preserve the early stop reason
        assert base_context.finish_reason == "perfect_score"

    def test_preserves_early_stop_reasons(self, optimizer, base_context) -> None:
        """Various early stop reasons should be preserved."""
        for early_reason in [
            "perfect_score",
            "no_improvement",
            "convergence",
            "error",
            "cancelled",
        ]:
            base_context.trials_completed = 10
            base_context.finish_reason = early_reason

            optimizer._finalize_finish_reason(base_context)

            assert base_context.finish_reason == early_reason

    def test_zero_trials_sets_completed(self, optimizer, base_context) -> None:
        """Edge case: 0 trials completed should set 'completed'."""
        base_context.trials_completed = 0
        base_context.max_trials = 10
        base_context.finish_reason = None

        optimizer._finalize_finish_reason(base_context)

        assert base_context.finish_reason == "completed"

    def test_zero_max_trials_edge_case(self, optimizer, base_context) -> None:
        """Edge case: max_trials=0, trials_completed=0 should set 'max_trials'."""
        base_context.trials_completed = 0
        base_context.max_trials = 0
        base_context.finish_reason = None

        optimizer._finalize_finish_reason(base_context)

        # 0 >= 0 is True, so should be "max_trials"
        assert base_context.finish_reason == "max_trials"


class TestBuildFinalResultStoppedEarly:
    """Tests for stopped_early and stop_reason in _build_final_result."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def base_context(self, simple_chat_prompt) -> OptimizationContext:
        """Create a context with required fields for _build_final_result."""
        return OptimizationContext(
            prompts={"main": simple_chat_prompt},
            initial_prompts={"main": simple_chat_prompt},
            is_single_prompt_optimization=True,
            dataset=MagicMock(id="ds-123"),
            evaluation_dataset=MagicMock(),
            validation_dataset=None,
            metric=MagicMock(__name__="test_metric"),
            agent=MagicMock(),
            optimization=None,
            optimization_id="opt-123",
            experiment_config=None,
            n_samples=None,
            max_trials=10,
            project_name="Test",
            baseline_score=0.5,
        )

    def test_stopped_early_true_for_max_trials(
        self, optimizer, base_context, simple_chat_prompt
    ) -> None:
        """stopped_early should be True when finish_reason is 'max_trials'."""
        from opik_optimizer.base_optimizer import AlgorithmResult

        base_context.finish_reason = "max_trials"
        base_context.trials_completed = 10

        algorithm_result = AlgorithmResult(
            best_prompts={"main": simple_chat_prompt},
            best_score=0.8,
            history=[],
            metadata={},
        )

        result = optimizer._build_final_result(algorithm_result, base_context)

        assert result.details["stopped_early"] is True
        assert result.details["stop_reason"] == "max_trials"
        assert result.details["finish_reason"] == "max_trials"

    def test_stopped_early_false_for_completed(
        self, optimizer, base_context, simple_chat_prompt
    ) -> None:
        """stopped_early should be False when finish_reason is 'completed'."""
        from opik_optimizer.base_optimizer import AlgorithmResult

        base_context.finish_reason = "completed"
        base_context.trials_completed = 5

        algorithm_result = AlgorithmResult(
            best_prompts={"main": simple_chat_prompt},
            best_score=0.8,
            history=[],
            metadata={},
        )

        result = optimizer._build_final_result(algorithm_result, base_context)

        assert result.details["stopped_early"] is False
        assert result.details["stop_reason"] == "completed"

    def test_stopped_early_true_for_perfect_score(
        self, optimizer, base_context, simple_chat_prompt
    ) -> None:
        """stopped_early should be True when finish_reason is 'perfect_score'."""
        from opik_optimizer.base_optimizer import AlgorithmResult

        base_context.finish_reason = "perfect_score"
        base_context.trials_completed = 3

        algorithm_result = AlgorithmResult(
            best_prompts={"main": simple_chat_prompt},
            best_score=0.99,
            history=[],
            metadata={},
        )

        result = optimizer._build_final_result(algorithm_result, base_context)

        assert result.details["stopped_early"] is True
        assert result.details["stop_reason"] == "perfect_score"

    def test_stopped_early_true_for_no_improvement(
        self, optimizer, base_context, simple_chat_prompt
    ) -> None:
        """stopped_early should be True when finish_reason is 'no_improvement'."""
        from opik_optimizer.base_optimizer import AlgorithmResult

        base_context.finish_reason = "no_improvement"
        base_context.trials_completed = 7

        algorithm_result = AlgorithmResult(
            best_prompts={"main": simple_chat_prompt},
            best_score=0.75,
            history=[],
            metadata={},
        )

        result = optimizer._build_final_result(algorithm_result, base_context)

        assert result.details["stopped_early"] is True
        assert result.details["stop_reason"] == "no_improvement"


class TestOptimizationContextDataclass:
    """Tests for OptimizationContext dataclass."""

    def test_creates_context_with_required_fields(self) -> None:
        """Should create context with all required fields."""
        mock_prompt = MagicMock()
        mock_dataset = MagicMock()
        mock_metric = MagicMock()
        mock_agent = MagicMock()

        context = OptimizationContext(
            prompts={"main": mock_prompt},
            initial_prompts={"main": mock_prompt},
            is_single_prompt_optimization=True,
            dataset=mock_dataset,
            evaluation_dataset=mock_dataset,
            validation_dataset=None,
            metric=mock_metric,
            agent=mock_agent,
            optimization=None,
            optimization_id=None,
            experiment_config=None,
            n_samples=None,
            max_trials=10,
            project_name="Test",
        )

        assert context.prompts == {"main": mock_prompt}
        assert context.is_single_prompt_optimization is True
        assert context.max_trials == 10
        assert context.baseline_score is None  # Default
        assert context.extra_params == {}  # Default

    def test_baseline_score_default(self) -> None:
        """baseline_score should default to None."""
        context = OptimizationContext(
            prompts={},
            initial_prompts={},
            is_single_prompt_optimization=True,
            dataset=MagicMock(),
            evaluation_dataset=MagicMock(),
            validation_dataset=None,
            metric=MagicMock(),
            agent=MagicMock(),
            optimization=None,
            optimization_id=None,
            experiment_config=None,
            n_samples=None,
            max_trials=10,
            project_name="Test",
        )

        assert context.baseline_score is None

    def test_extra_params_default(self) -> None:
        """extra_params should default to empty dict."""
        context = OptimizationContext(
            prompts={},
            initial_prompts={},
            is_single_prompt_optimization=True,
            dataset=MagicMock(),
            evaluation_dataset=MagicMock(),
            validation_dataset=None,
            metric=MagicMock(),
            agent=MagicMock(),
            optimization=None,
            optimization_id=None,
            experiment_config=None,
            n_samples=None,
            max_trials=10,
            project_name="Test",
        )

        assert context.extra_params == {}


class TestDefaultOptimizePrompt:
    """Tests for the default optimize_prompt implementation in BaseOptimizer."""

    @pytest.fixture
    def mock_metric(self):
        def metric(dataset_item, llm_output):
            return 1.0

        metric.__name__ = "test_metric"
        return metric

    def test_early_stops_on_perfect_baseline(
        self,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
        monkeypatch,
    ) -> None:
        """Should return early result when baseline score meets threshold."""
        mock_opik_client()
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)
        mock_ds.name = "test-dataset"
        mock_ds.id = "ds-123"
        mock_ds.get_items.return_value = [{"id": "1", "input": "test"}]

        # Create optimizer that uses default optimize_prompt
        class DefaultOptimizer(BaseOptimizer):
            def run_optimization(self, context: OptimizationContext):
                raise AssertionError("Should not be called")

            def get_config(self, context: OptimizationContext):
                return {"optimizer": "DefaultOptimizer"}

            def get_optimizer_metadata(self):
                return {}

        optimizer = DefaultOptimizer(model="gpt-4", perfect_score=0.9)
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.95)

        result = optimizer.optimize_prompt(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            max_trials=10,
        )

        assert result.details["stopped_early"] is True
        assert result.details["stop_reason"] == "baseline_score_met_threshold"

    def test_calls_run_optimization_when_baseline_below_threshold(
        self,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
        monkeypatch,
    ) -> None:
        """Should call _run_optimization when baseline doesn't meet threshold."""
        mock_opik_client()
        from opik import Dataset
        from opik_optimizer.optimization_result import OptimizationResult

        mock_ds = MagicMock(spec=Dataset)
        mock_ds.name = "test-dataset"
        mock_ds.id = "ds-123"
        mock_ds.get_items.return_value = [{"id": "1", "input": "test"}]

        run_optimization_called = []

        class DefaultOptimizer(BaseOptimizer):
            def run_optimization(self, context: OptimizationContext):
                run_optimization_called.append(True)
                return OptimizationResult(
                    optimizer="DefaultOptimizer",
                    prompt=list(context.prompts.values())[0],
                    score=0.8,
                    metric_name="test_metric",
                )

            def get_config(self, context: OptimizationContext):
                return {"optimizer": "DefaultOptimizer"}

            def get_optimizer_metadata(self):
                return {}

        optimizer = DefaultOptimizer(model="gpt-4", perfect_score=0.9)
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        result = optimizer.optimize_prompt(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            max_trials=10,
        )

        assert len(run_optimization_called) == 1
        assert result.score == 0.8

    def test_early_stop_reports_at_least_one_trial(
        self,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
        monkeypatch,
    ) -> None:
        """Early stop should report at least 1 trial/round completed (baseline evaluation)."""
        mock_opik_client()
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)
        mock_ds.name = "test-dataset"
        mock_ds.id = "ds-123"
        mock_ds.get_items.return_value = [{"id": "1", "input": "test"}]

        class DefaultOptimizer(BaseOptimizer):
            def run_optimization(self, context: OptimizationContext):
                raise AssertionError("Should not be called")

            def get_config(self, context: OptimizationContext):
                return {"optimizer": "DefaultOptimizer"}

            def get_optimizer_metadata(self):
                return {}

        optimizer = DefaultOptimizer(model="gpt-4", perfect_score=0.9)
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.95)

        result = optimizer.optimize_prompt(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            max_trials=10,
        )

        # Early stop should report actual work done, not 0
        assert result.details["stopped_early"] is True
        # At least 1 trial must have completed (baseline evaluation counts as a trial)
        assert result.details.get("trials_completed", 1) >= 1

    def test_early_stop_uses_optimizer_provided_counts(
        self,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
        monkeypatch,
    ) -> None:
        """Early stop should use optimizer-provided trial/round counts if available."""
        mock_opik_client()
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)
        mock_ds.name = "test-dataset"
        mock_ds.id = "ds-123"
        mock_ds.get_items.return_value = [{"id": "1", "input": "test"}]

        class CustomOptimizer(BaseOptimizer):
            def run_optimization(self, context: OptimizationContext):
                raise AssertionError("Should not be called")

            def get_config(self, context: OptimizationContext):
                return {"optimizer": "CustomOptimizer"}

            def get_optimizer_metadata(self):
                return {}

            def get_metadata(self, context: OptimizationContext):
                # Optimizer reports it tracked 3 trials before early stop
                return {
                    "trials_completed": 3,
                    "rounds_completed": 2,
                    "custom_field": "test_value",
                }

        optimizer = CustomOptimizer(model="gpt-4", perfect_score=0.9)
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.95)

        result = optimizer.optimize_prompt(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            max_trials=10,
        )

        # Should use optimizer's counts
        assert result.details["stopped_early"] is True
        assert result.details["trials_completed"] == 3
        assert result.details["rounds_completed"] == 2
        assert result.details["custom_field"] == "test_value"
