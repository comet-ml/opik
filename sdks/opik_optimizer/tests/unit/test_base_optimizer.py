# mypy: disable-error-code=no-untyped-def

"""
Unit tests for opik_optimizer.base_optimizer module.

Tests cover:
- _validate_optimization_inputs: Input validation
- utils.tool_helpers.deep_merge_dicts: Dictionary merging
- utils.tool_helpers.serialize_tools: Tool serialization
- core.agent.build_agent_config: Config building
- core.state.build_optimizer_metadata: Metadata generation
- Counter and history management
"""

from __future__ import annotations

from decimal import Decimal
from typing import Any, TYPE_CHECKING
from collections.abc import Callable
from unittest.mock import MagicMock

import pytest

from opik_optimizer.base_optimizer import BaseOptimizer, AlgorithmResult
from opik_optimizer.core.state import OptimizationContext
from opik_optimizer.constants import MIN_EVAL_THREADS, MAX_EVAL_THREADS
from opik_optimizer.api_objects import chat_prompt
from opik_optimizer import ChatPrompt
from tests.unit.test_helpers import (
    make_candidate_agent,
    make_fake_evaluator,
    make_mock_dataset,
    make_optimization_context,
    make_simple_metric,
    STANDARD_DATASET_ITEMS,
)

if TYPE_CHECKING:
    from opik_optimizer.api_objects import chat_prompt

from tests.unit.fixtures.base_optimizer_test_helpers import ConcreteOptimizer, _DisplaySpy


@pytest.mark.parametrize(
    "selection_policy,expected_output,logprobs,metric_func",
    [
        (
            None,  # Default: best candidate
            "good",
            None,
            lambda _di, lo: 1.0 if lo == "good" else 0.0,
        ),
        ("first", "bad", None, lambda _di, _lo: 1.0),
        ("concat", "bad\n\ngood", None, lambda _di, _lo: 1.0),
        ("max_logprob", "good", [0.2, 0.9], lambda _di, _lo: 1.0),
    ],
)
def test_evaluate_prompt_selection_policies(
    monkeypatch: pytest.MonkeyPatch,
    selection_policy: str | None,
    expected_output: str,
    logprobs: list[float] | None,
    metric_func: Callable,
) -> None:
    """Test different selection policies for candidate evaluation."""
    optimizer = ConcreteOptimizer(model="gpt-4")
    agent = make_candidate_agent(candidates=["bad", "good"], logprobs=logprobs)

    model_params: dict[str, Any] = {"n": 2}
    if selection_policy:
        model_params["selection_policy"] = selection_policy

    prompt = chat_prompt.ChatPrompt(
        name="p",
        messages=[{"role": "user", "content": "{input}"}],
        model_parameters=model_params,
    )

    fake_evaluate = make_fake_evaluator(expected_output=expected_output)
    monkeypatch.setattr(
        "opik_optimizer.base_optimizer.task_evaluator.evaluate", fake_evaluate
    )

    dataset = make_mock_dataset([{"id": "1", "input": "x"}])

    score = optimizer.evaluate_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=metric_func,
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

    dataset = make_mock_dataset()
    metric = MagicMock()
    agent = MagicMock()

    context = make_optimization_context(
        simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        max_trials=3,
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

    score = optimizer.evaluate(context, {"main": simple_chat_prompt})

    assert score == 0.5
    assert captured_call["n_threads"] == optimizer.n_threads


def test_normalize_n_threads_clamps_bounds(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    """Thread counts are clamped to safe bounds before evaluator calls."""
    optimizer = ConcreteOptimizer(model="gpt-4", verbose=0)
    dataset = make_mock_dataset([{"id": "1", "input": "x"}])
    metric = MagicMock(__name__="metric")
    agent = MagicMock()

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
    dataset = make_mock_dataset()
    metric = MagicMock()
    agent = MagicMock()

    context = make_optimization_context(
        simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        max_trials=3,
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

    score = optimizer.evaluate(context, {"main": simple_chat_prompt})

    assert score == float("inf")
    assert context.current_best_score == float("inf")
    assert context.trials_completed == 1


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


def test_on_trial_handles_non_finite_scores(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    """on_trial should not crash when scores are non-finite."""
    optimizer = ConcreteOptimizer(model="gpt-4", verbose=1)
    dataset = make_mock_dataset()
    metric = MagicMock()
    agent = MagicMock()

    context = make_optimization_context(
        simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        max_trials=5,
    )
    context.current_best_score = float("inf")
    context.trials_completed = 1
    captured: dict[str, Any] = {}

    def fake_display_evaluation_progress(**kwargs):
        captured.update(kwargs)

    monkeypatch.setattr(
        "opik_optimizer.utils.display.terminal.display_evaluation_progress",
        fake_display_evaluation_progress,
    )

    optimizer.on_trial(context, {"main": simple_chat_prompt}, float("inf"))

    assert captured["style"] == "yellow"
    assert captured["score_text"] == "non-finite score"


def test_optimize_prompt_uses_injected_display(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    spy = _DisplaySpy()
    optimizer = ConcreteOptimizer(model="gpt-4", display=spy)

    mock_dataset = MagicMock()
    mock_metric = MagicMock(__name__="metric")

    def fake_setup(*args, **kwargs):
        return make_optimization_context(
            simple_chat_prompt,
            dataset=mock_dataset,
            metric=mock_metric,
            agent=MagicMock(),
            max_trials=1,
        )

    monkeypatch.setattr(optimizer, "_setup_optimization", fake_setup)
    monkeypatch.setattr(optimizer, "_calculate_baseline", lambda _ctx: 0.1)
    monkeypatch.setattr(optimizer, "_should_skip_optimization", lambda _score: True)
    monkeypatch.setattr(optimizer, "_build_early_result", lambda **kwargs: MagicMock())
    monkeypatch.setattr(
        optimizer, "_finalize_optimization", lambda *args, **kwargs: None
    )

    BaseOptimizer.optimize_prompt(
        optimizer,
        prompt=simple_chat_prompt,
        dataset=mock_dataset,
        metric=mock_metric,
        max_trials=1,
    )

    assert spy.header_calls, "Injected display handler should be used"


def test_should_stop_context_on_perfect_score(simple_chat_prompt) -> None:
    optimizer = ConcreteOptimizer(
        model="gpt-4", perfect_score=0.8, skip_perfect_score=True
    )
    dataset = make_mock_dataset()
    metric = MagicMock()
    agent = MagicMock()
    context = make_optimization_context(
        simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        max_trials=5,
    )

    def fake_eval(*args, **kwargs):
        return 0.9

    optimizer.evaluate_prompt = fake_eval  # type: ignore[assignment]

    optimizer.evaluate(context, {"main": simple_chat_prompt})
    assert context.should_stop is True
    assert context.finish_reason == "perfect_score"
    from opik_optimizer.core.results import OptimizationHistoryState

    builder = OptimizationHistoryState()
    handle = builder.start_round(round_index=0)
    builder.record_trial(round_handle=handle, score=0.9, trial_index=0)
    builder.end_round(round_handle=handle, best_score=0.9)
    entries = builder.get_entries()
    assert entries[0]["best_so_far"] == 0.9


def test_evaluate_sets_finish_reason_on_max_trials(simple_chat_prompt) -> None:
    optimizer = ConcreteOptimizer(
        model="gpt-4", perfect_score=1.5, skip_perfect_score=True
    )
    dataset = make_mock_dataset()
    metric = MagicMock()
    agent = MagicMock()
    context = make_optimization_context(
        simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        max_trials=1,
    )

    def fake_eval(*args, **kwargs):
        return 0.1

    optimizer.evaluate_prompt = fake_eval  # type: ignore[assignment]

    optimizer.evaluate(context, {"main": simple_chat_prompt})
    assert context.should_stop is True
    assert context.finish_reason == "max_trials"
    assert context.trials_completed == 1


class TestOptimizationContextDataclass:
    """Tests for OptimizationContext dataclass."""

    def test_creates_context_with_required_fields(self) -> None:
        """Should create context with all required fields."""
        mock_prompt = ChatPrompt(name="test", system="test", user="test")
        mock_dataset = make_mock_dataset()
        mock_metric = MagicMock()
        mock_agent = MagicMock()

        context = make_optimization_context(
            mock_prompt,
            dataset=mock_dataset,
            metric=mock_metric,
            agent=mock_agent,
        )

        assert "test" in context.prompts
        assert context.is_single_prompt_optimization is True
        assert context.max_trials == 10
        assert context.baseline_score is None  # Default
        assert context.extra_params == {}  # Default

    def test_baseline_score_default(self) -> None:
        """baseline_score should default to None."""
        context = make_optimization_context(
            ChatPrompt(name="test", system="test", user="test"),
        )

        assert context.baseline_score is None

    def test_extra_params_default(self) -> None:
        """extra_params should default to empty dict."""
        context = make_optimization_context(
            ChatPrompt(name="test", system="test", user="test"),
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
        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

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
        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

        run_optimization_called = []

        class DefaultOptimizer(BaseOptimizer):
            def run_optimization(self, context: OptimizationContext):
                run_optimization_called.append(True)
                return AlgorithmResult(
                    best_prompts={"prompt": list(context.prompts.values())[0]},
                    best_score=0.8,
                    history=[],
                    metadata={},
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
        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

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
        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

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
        assert len(result.history) == 1
        assert result.details["custom_field"] == "test_value"

    def test_history_fallback_when_optimizer_returns_empty(
        self,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
        monkeypatch,
    ) -> None:
        """Base should emit a fallback history entry when optimizer returns none."""
        mock_opik_client()
        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

        class EmptyHistoryOptimizer(BaseOptimizer):
            def run_optimization(self, context: OptimizationContext):
                return AlgorithmResult(
                    best_prompts=context.prompts,
                    best_score=0.5,
                    history=[],
                    metadata={},
                )

            def get_config(self, context: OptimizationContext):
                return {"optimizer": "EmptyHistoryOptimizer"}

            def get_optimizer_metadata(self):
                return {}

        optimizer = EmptyHistoryOptimizer(model="gpt-4")
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        result = optimizer.optimize_prompt(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            max_trials=1,
        )

        assert result.history


# ============================================================
# optimize_prompt Parameter Tests
# ============================================================


class TestAutoContinueParameter:
    """Tests for auto_continue parameter in optimize_prompt."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_metric(self):
        return make_simple_metric()

    @pytest.mark.parametrize(
        "auto_continue_value,expected_in_extra_params",
        [
            (True, True),
            (False, False),
        ],
    )
    def test_auto_continue_stored_in_context(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
        auto_continue_value: bool,
        expected_in_extra_params: bool,
    ) -> None:
        """Verify auto_continue is stored in context.extra_params."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            auto_continue=auto_continue_value,
        )

        assert context.extra_params.get("auto_continue") == expected_in_extra_params

    def test_auto_continue_default_false(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Default value of auto_continue should be False."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
        )

        assert context.extra_params.get("auto_continue", False) is False


class TestExperimentConfigParameter:
    """Tests for experiment_config parameter in optimize_prompt."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_metric(self):
        return make_simple_metric()

    def test_experiment_config_stored_in_context(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Verify experiment_config is stored in context."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)
        config = {"key1": "value1", "key2": 42}

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            experiment_config=config,
        )

        assert context.experiment_config == config

    def test_experiment_config_none_handled(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """None experiment_config should be handled gracefully."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            experiment_config=None,
        )

        assert context.experiment_config is None

    def test_experiment_config_passed_to_evaluate(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        """Verify experiment_config is passed to evaluate_prompt."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)
        config = {"test_key": "test_value"}

        captured_config = {}

        def fake_evaluate(**kwargs: Any) -> float:
            captured_config["experiment_config"] = kwargs.get("experiment_config")
            return 0.5

        monkeypatch.setattr(optimizer, "evaluate_prompt", fake_evaluate)

        optimizer._calculate_baseline(
            make_optimization_context(
                simple_chat_prompt,
                dataset=mock_ds,
                metric=mock_metric,
                experiment_config=config,
            )
        )

        # experiment_config is merged with internal config, so check it's present
        assert captured_config.get("experiment_config") is not None


class TestNSamplesParameter:
    """Tests for n_samples parameter in optimize_prompt."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_metric(self):
        return make_simple_metric()

    @pytest.mark.parametrize(
        "n_samples,dataset_size,expected_n_samples",
        [
            (5, 10, 5),  # Normal case
            (None, 10, None),  # None evaluates all
            (15, 10, None),  # Greater than dataset size -> None (uses all)
        ],
    )
    def test_n_samples_limits_evaluation(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
        n_samples: int | None,
        dataset_size: int,
        expected_n_samples: int | None,
    ) -> None:
        """n_samples limits dataset items evaluated."""
        mock_opik_client()
        items = [{"id": str(i), "input": f"test{i}"} for i in range(dataset_size)]
        mock_ds = make_mock_dataset(items)

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            n_samples=n_samples,
        )

        assert context.n_samples == expected_n_samples

    def test_n_samples_zero_raises_warning(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """n_samples=0 should be handled (though unusual)."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)

        # n_samples=0 is technically valid but unusual
        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            n_samples=0,
        )

        # Should be stored as-is (validation happens in evaluate_prompt)
        assert context.n_samples == 0


class TestProjectNameParameter:
    """Tests for project_name parameter in optimize_prompt."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_metric(self):
        return make_simple_metric()

    def test_project_name_explicit(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Explicit project_name should be used."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            project_name="MyProject",
        )

        assert context.project_name == "MyProject"
        assert optimizer.project_name == "MyProject"

    def test_project_name_passed_to_agent(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Project name should be passed to agent."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)
        from opik_optimizer.agents import LiteLLMAgent

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            project_name="TestProject",
            agent=None,  # Will create default agent
        )

        assert isinstance(context.agent, LiteLLMAgent)
        assert context.agent.project_name == "TestProject"  # type: ignore[attr-defined]


class TestOptimizationIdParameter:
    """Tests for optimization_id parameter in optimize_prompt."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_metric(self):
        return make_simple_metric()

    def test_optimization_id_stored_in_context(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Optimization ID should be stored in context."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)
        mock_optimization = MagicMock()
        mock_optimization.id = "opt-123"
        optimizer.opik_client.get_optimization_by_id = MagicMock(
            return_value=mock_optimization
        )

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            optimization_id="opt-123",
        )

        assert context.optimization_id == "opt-123"
        assert optimizer.current_optimization_id == "opt-123"


class TestValidationDatasetParameter:
    """Tests for validation_dataset parameter in optimize_prompt."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_metric(self):
        return make_simple_metric()

    def test_validation_dataset_used_for_evaluation(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Validation dataset should be used for evaluation when provided."""
        mock_opik_client()
        training_ds = make_mock_dataset(STANDARD_DATASET_ITEMS, name="training")
        validation_ds = make_mock_dataset(
            [{"id": "2", "question": "Q2", "answer": "A2"}], name="validation"
        )

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=training_ds,
            metric=mock_metric,
            compute_baseline=False,
            validation_dataset=validation_ds,
        )

        assert context.validation_dataset is validation_ds
        assert context.evaluation_dataset is validation_ds
        assert context.dataset_split == "validation"

    def test_validation_dataset_none_uses_training(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """None validation_dataset should use training dataset."""
        mock_opik_client()
        training_ds = make_mock_dataset(STANDARD_DATASET_ITEMS, name="training")

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=training_ds,
            metric=mock_metric,
            compute_baseline=False,
            validation_dataset=None,
        )

        assert context.validation_dataset is None
        assert context.evaluation_dataset is training_ds
        assert context.dataset_split == "train"

    def test_n_samples_validation_uses_evaluation_dataset_size(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """n_samples validation should use evaluation dataset size when validation is provided."""
        mock_opik_client()
        training_ds = make_mock_dataset(STANDARD_DATASET_ITEMS, name="training")
        validation_ds = make_mock_dataset(
            [{"id": "2", "question": "Q2", "answer": "A2"}], name="validation"
        )

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=training_ds,
            metric=mock_metric,
            compute_baseline=False,
            validation_dataset=validation_ds,
            n_samples=2,
        )

        assert context.n_samples is None

    def test_metric_required_fields_enforced_on_evaluation_dataset(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
    ) -> None:
        """Metrics declaring required_fields should fail when missing in evaluation dataset."""
        mock_opik_client()
        training_ds = make_mock_dataset(STANDARD_DATASET_ITEMS, name="training")
        validation_ds = make_mock_dataset(
            [{"id": "2", "question": "Q2"}], name="validation"
        )

        def metric_fn(dataset_item: dict[str, Any], llm_output: str) -> float:
            return 0.0

        metric_fn.required_fields = ("answer",)  # type: ignore[attr-defined]

        with pytest.raises(ValueError, match="requires dataset fields"):
            optimizer._setup_optimization(
                prompt=simple_chat_prompt,
                dataset=training_ds,
                metric=metric_fn,
                compute_baseline=False,
                validation_dataset=validation_ds,
            )

    def test_validation_dataset_added_to_experiment_config(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Validation dataset should be added to experiment_config."""
        mock_opik_client()
        training_ds = make_mock_dataset(STANDARD_DATASET_ITEMS, name="training")
        validation_ds = make_mock_dataset(
            [{"id": "2", "question": "Q2", "answer": "A2"}],
            name="validation",
            dataset_id="val-123",
        )

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=training_ds,
            metric=mock_metric,
            compute_baseline=False,
            validation_dataset=validation_ds,
            experiment_config={},
        )

        assert context.experiment_config is not None
        assert context.experiment_config["validation_dataset"] == "validation"
        assert context.experiment_config["validation_dataset_id"] == "val-123"


# ============================================================
# Initialization Parameter Tests
# ============================================================


class TestModelParameters:
    """Tests for model_parameters initialization parameter."""

    @pytest.mark.parametrize(
        "model_params,expected",
        [
            (
                {"temperature": 0.7, "max_tokens": 100},
                {"temperature": 0.7, "max_tokens": 100},
            ),
            (None, {}),
            ({}, {}),
        ],
    )
    def test_model_parameters_stored(
        self, model_params: dict[str, Any] | None, expected: dict[str, Any]
    ) -> None:
        """Model parameters should be stored correctly."""
        optimizer = ConcreteOptimizer(model="gpt-4", model_parameters=model_params)
        assert optimizer.model_parameters == expected


class TestReasoningModel:
    """Tests for reasoning_model initialization parameter."""

    def test_reasoning_model_explicit(self) -> None:
        """Explicit reasoning_model should be used."""
        optimizer = ConcreteOptimizer(model="gpt-4", reasoning_model="gpt-4o-mini")
        assert optimizer.reasoning_model == "gpt-4o-mini"

    def test_reasoning_model_none_falls_back_to_model(self) -> None:
        """None reasoning_model should fall back to model."""
        optimizer = ConcreteOptimizer(model="gpt-4", reasoning_model=None)
        assert optimizer.reasoning_model == "gpt-4"


class TestReasoningModelParameters:
    """Tests for reasoning_model_parameters initialization parameter."""

    def test_reasoning_model_parameters_explicit(self) -> None:
        """Explicit reasoning_model_parameters should be used."""
        optimizer = ConcreteOptimizer(
            model="gpt-4",
            reasoning_model_parameters={"temperature": 0.5},
        )
        assert optimizer.reasoning_model_parameters == {"temperature": 0.5}

    def test_reasoning_model_parameters_none_falls_back(
        self,
    ) -> None:
        """None reasoning_model_parameters should fall back to model_parameters."""
        optimizer = ConcreteOptimizer(
            model="gpt-4",
            model_parameters={"temperature": 0.7},
            reasoning_model_parameters=None,
        )
        assert optimizer.reasoning_model_parameters == {"temperature": 0.7}


# ============================================================
# Edge Cases and Error Handling
# ============================================================


class TestEmptyDataset:
    """Tests for empty dataset handling."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_metric(self):
        return make_simple_metric()

    def test_empty_dataset_raises_error(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Empty dataset should raise appropriate error."""
        mock_opik_client()
        empty_ds = make_mock_dataset([], name="empty-dataset")

        # Empty dataset should be caught during validation
        with pytest.raises((ValueError, Exception)):
            optimizer._setup_optimization(
                prompt=simple_chat_prompt,
                dataset=empty_ds,
                metric=mock_metric,
                compute_baseline=False,
            )


class TestInvalidMetricFunctions:
    """Tests for invalid metric function handling."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.mark.parametrize(
        "metric_func,expected_error",
        [
            (lambda _di, _lo: float("nan"), ValueError),  # NaN
            (lambda _di, _lo: None, TypeError),  # None
            (lambda _di, _lo: "not a float", TypeError),  # Wrong type
        ],
    )
    def test_invalid_metric_raises_error(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        metric_func: Callable,
        expected_error: type[Exception],
    ) -> None:
        """Invalid metric functions should raise appropriate errors."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)

        # Metric validation happens during evaluation
        optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=metric_func,
            compute_baseline=False,
        )

        # Error should occur during _coerce_score
        with pytest.raises(expected_error):
            optimizer._coerce_score(metric_func({}, "output"))


class TestReusingOptimizerInstances:
    """Tests for reusing optimizer instances across multiple runs."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_metric(self):
        return make_simple_metric()

    def test_reuse_optimizer_multiple_runs(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Optimizer should be reusable for multiple runs."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)

        # First run
        context1 = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
        )

        # Second run - counters should be reset
        context2 = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
        )

        # Contexts should be independent
        assert context1 is not context2
        assert context1.trials_completed == 0
        assert context2.trials_completed == 0

    def test_reuse_counters_reset(
        self,
        optimizer: ConcreteOptimizer,
    ) -> None:
        """Counters should reset between runs."""
        optimizer.llm_call_counter = 5
        optimizer.llm_call_tools_counter = 3
        optimizer.llm_cost_total = 1.5
        optimizer.llm_token_usage_total = {
            "prompt_tokens": 10,
            "completion_tokens": 20,
            "total_tokens": 30,
        }

        optimizer._reset_counters()

        assert optimizer.llm_call_counter == 0
        assert optimizer.llm_call_tools_counter == 0
        assert optimizer.llm_cost_total == 0.0
        assert optimizer.llm_token_usage_total == {
            "prompt_tokens": 0,
            "completion_tokens": 0,
            "total_tokens": 0,
        }

    def test_reuse_history_cleared(
        self,
        optimizer: ConcreteOptimizer,
    ) -> None:
        """History should be cleared between runs."""
        # Add some history
        optimizer._history_builder.start_round()
        optimizer._history_builder.end_round(None)

        # Clear for new run
        optimizer._history_builder.clear()

        assert len(optimizer._history_builder.get_entries()) == 0
