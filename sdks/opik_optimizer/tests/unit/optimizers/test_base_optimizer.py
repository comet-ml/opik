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
from typing import Any, TYPE_CHECKING, cast
from collections.abc import Callable
from unittest.mock import MagicMock

import pytest

from opik_optimizer.base_optimizer import BaseOptimizer, AlgorithmResult
from opik_optimizer.core import agent as agent_utils
from opik_optimizer.core import state as state_utils
from opik_optimizer.core.state import OptimizationContext
from opik_optimizer.constants import MIN_EVAL_THREADS, MAX_EVAL_THREADS
from opik_optimizer.api_objects import chat_prompt
from opik_optimizer import ChatPrompt
from opik_optimizer.utils import tool_helpers as tool_utils
from tests.unit.test_helpers import (
    make_candidate_agent,
    make_fake_evaluator,
    make_mock_dataset,
    make_optimization_context,
    make_simple_metric,
    STANDARD_DATASET_ITEMS,
)

if TYPE_CHECKING:
    from opik import Dataset
    from opik_optimizer.agents import OptimizableAgent
    from opik_optimizer.api_objects import chat_prompt
    from opik_optimizer.api_objects.types import MetricFunction
    from opik_optimizer.core.results import OptimizationResult


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


class _ToolFlagAgent(MagicMock):
    def __init__(self) -> None:
        super().__init__()
        self.last_allow_tool_use: bool | None = None

    def invoke_agent(self, prompts, dataset_item, allow_tool_use=False, seed=None):
        _ = prompts, dataset_item, seed
        self.last_allow_tool_use = allow_tool_use
        return "ok"


class _DisplaySpy:
    def __init__(self) -> None:
        self.header_calls: list[tuple[str, str | None]] = []

    def show_header(
        self,
        *,
        algorithm: str,
        optimization_id: str | None,
        dataset_id: str | None = None,
    ) -> None:
        self.header_calls.append((algorithm, optimization_id))

    def show_configuration(self, *, prompt, optimizer_config) -> None:
        pass

    def baseline_evaluation(self, context) -> Any:
        from contextlib import contextmanager

        @contextmanager
        def _cm():
            class Reporter:
                def set_score(self, score: float) -> None:
                    pass

            yield Reporter()

        return _cm()

    def evaluation_progress(
        self, *, context, prompts, score, display_info=None
    ) -> None:
        pass

    def show_final_result(self, *, initial_score, best_score, prompt) -> None:
        pass


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
        mock_ds = make_mock_dataset()

        # Should not raise
        optimizer._validate_optimization_inputs(
            simple_chat_prompt, mock_ds, mock_metric
        )

    def test_accepts_valid_prompt_dict(
        self, optimizer, simple_chat_prompt, mock_metric
    ) -> None:
        """Should accept a dict of ChatPrompt objects."""
        mock_ds = make_mock_dataset()
        prompt_dict = {"main": simple_chat_prompt}

        # Should not raise
        optimizer._validate_optimization_inputs(prompt_dict, mock_ds, mock_metric)

    def test_rejects_non_chatprompt(self, optimizer, mock_metric) -> None:
        """Should reject prompt that is not a ChatPrompt."""
        mock_ds = make_mock_dataset()

        with pytest.raises(ValueError, match="ChatPrompt"):
            optimizer._validate_optimization_inputs(
                "not a prompt", mock_ds, mock_metric
            )

    def test_rejects_dict_with_non_chatprompt_values(
        self, optimizer, mock_metric
    ) -> None:
        """Should reject dict containing non-ChatPrompt values."""
        mock_ds = make_mock_dataset()
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
        mock_ds = make_mock_dataset()

        with pytest.raises(ValueError, match="function"):
            optimizer._validate_optimization_inputs(
                simple_chat_prompt, mock_ds, "not a function"
            )

    def test_rejects_multimodal_when_not_supported(
        self, optimizer, multimodal_chat_prompt, mock_metric
    ) -> None:
        """Should reject multimodal prompts when support_content_parts=False."""
        mock_ds = make_mock_dataset()

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
        mock_ds = make_mock_dataset()

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


class TestDeepMergeDicts:
    """Tests for utils.tool_helpers.deep_merge_dicts."""

    def test_merges_flat_dicts(self) -> None:
        """Should merge two flat dictionaries."""
        base = {"a": 1, "b": 2}
        overrides = {"b": 3, "c": 4}

        result = tool_utils.deep_merge_dicts(base, overrides)

        assert result == {"a": 1, "b": 3, "c": 4}

    def test_deep_merges_nested_dicts(self) -> None:
        """Should recursively merge nested dictionaries."""
        base = {"level1": {"a": 1, "b": 2}, "other": "value"}
        overrides = {"level1": {"b": 3, "c": 4}}

        result = tool_utils.deep_merge_dicts(base, overrides)

        assert result == {"level1": {"a": 1, "b": 3, "c": 4}, "other": "value"}

    def test_override_replaces_non_dict_with_dict(self) -> None:
        """Should replace non-dict value with dict value."""
        base = {"key": "string_value"}
        overrides = {"key": {"nested": "value"}}

        result = tool_utils.deep_merge_dicts(base, overrides)

        assert result == {"key": {"nested": "value"}}

    def test_override_replaces_dict_with_non_dict(self) -> None:
        """Should replace dict value with non-dict value."""
        base = {"key": {"nested": "value"}}
        overrides = {"key": "string_value"}

        result = tool_utils.deep_merge_dicts(base, overrides)

        assert result == {"key": "string_value"}

    def test_does_not_modify_original_dicts(self) -> None:
        """Should not modify the input dictionaries."""
        base = {"a": {"b": 1}}
        overrides = {"a": {"c": 2}}

        tool_utils.deep_merge_dicts(base, overrides)

        assert base == {"a": {"b": 1}}
        assert overrides == {"a": {"c": 2}}

    def test_handles_empty_base(self) -> None:
        """Should handle empty base dictionary."""
        result = tool_utils.deep_merge_dicts({}, {"a": 1})
        assert result == {"a": 1}

    def test_handles_empty_overrides(self) -> None:
        """Should handle empty overrides dictionary."""
        result = tool_utils.deep_merge_dicts({"a": 1}, {})
        assert result == {"a": 1}


class TestSerializeTools:
    """Tests for utils.tool_helpers.serialize_tools."""

    def test_serializes_tools_list(self, chat_prompt_with_tools) -> None:
        """Should return deep copy of tools list."""
        result = tool_utils.serialize_tools(chat_prompt_with_tools)

        assert isinstance(result, list)
        assert len(result) == 2
        assert result[0]["function"]["name"] == "search"

    def test_returns_empty_list_when_no_tools(self, simple_chat_prompt) -> None:
        """Should return empty list when prompt has no tools."""
        result = tool_utils.serialize_tools(simple_chat_prompt)

        assert result == []

    def test_returns_deep_copy(self, chat_prompt_with_tools) -> None:
        """Should return a deep copy, not reference original."""
        result = tool_utils.serialize_tools(chat_prompt_with_tools)

        # Modify the result
        result[0]["function"]["name"] = "modified"

        # Original should be unchanged
        assert chat_prompt_with_tools.tools[0]["function"]["name"] == "search"


class TestBuildAgentConfig:
    """Tests for core.agent.build_agent_config."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    def test_includes_prompt_dict(self, optimizer, simple_chat_prompt) -> None:
        """Should include prompt content from to_dict()."""
        config = agent_utils.build_agent_config(
            optimizer=optimizer, prompt=simple_chat_prompt
        )

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
        config = agent_utils.build_agent_config(
            optimizer=optimizer, prompt=simple_chat_prompt
        )

        assert "model" in config

    def test_includes_optimizer_name(self, optimizer, simple_chat_prompt) -> None:
        """Should include optimizer class name."""
        config = agent_utils.build_agent_config(
            optimizer=optimizer, prompt=simple_chat_prompt
        )

        assert config["optimizer"] == "ConcreteOptimizer"

    def test_includes_tools(self, optimizer, chat_prompt_with_tools) -> None:
        """Should include serialized tools."""
        config = agent_utils.build_agent_config(
            optimizer=optimizer, prompt=chat_prompt_with_tools
        )

        assert "tools" in config
        assert len(config["tools"]) == 2


class TestGetOptimizerMetadata:
    """Tests for get_optimizer_metadata and build_optimizer_metadata."""

    def test_subclass_metadata_is_included(self) -> None:
        """Subclass metadata should be included via get_optimizer_metadata."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        metadata = state_utils.build_optimizer_metadata(optimizer)

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

        metadata = state_utils.build_optimizer_metadata(optimizer)

        assert metadata["name"] == "ConcreteOptimizer"
        assert metadata["model"] == "gpt-4"
        assert metadata["seed"] == 123
        assert metadata["model_parameters"] == {"temperature": 0.5}

    def test_includes_version(self) -> None:
        """Should include optimizer version."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        metadata = state_utils.build_optimizer_metadata(optimizer)

        assert "version" in metadata


class TestBuildOptimizationMetadata:
    """Tests for build_optimization_metadata."""

    def test_includes_optimizer_name(self) -> None:
        """Should include optimizer class name."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        metadata = state_utils.build_optimization_metadata(optimizer)

        assert metadata["optimizer"] == "ConcreteOptimizer"

    def test_includes_custom_name_when_set(self) -> None:
        """Should include custom name when provided."""
        optimizer = ConcreteOptimizer(model="gpt-4", name="my-optimization")

        metadata = state_utils.build_optimization_metadata(optimizer)

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

        metadata = state_utils.build_optimization_metadata(
            optimizer, agent_class=CustomAgent
        )

        assert metadata["agent_class"] == "CustomAgent"


class TestCounterManagement:
    """Tests for counter management methods."""

    def test_counters_start_at_zero(self) -> None:
        """Counters should start at zero."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        assert optimizer.llm_call_counter == 0
        assert optimizer.llm_call_tools_counter == 0

    def test_increment_llm_counter(self) -> None:
        """_increment_llm_counter should increment the counter."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        optimizer._increment_llm_counter()
        optimizer._increment_llm_counter()

        assert optimizer.llm_call_counter == 2

    def test_increment_llm_call_tools_counter(self) -> None:
        """_increment_llm_call_tools_counter should increment the counter."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        optimizer._increment_llm_call_tools_counter()

        assert optimizer.llm_call_tools_counter == 1

    def test_reset_counters(self) -> None:
        """_reset_counters should reset both counters to zero."""
        optimizer = ConcreteOptimizer(model="gpt-4")
        optimizer._increment_llm_counter()
        optimizer._increment_llm_call_tools_counter()

        optimizer._reset_counters()

        assert optimizer.llm_call_counter == 0
        assert optimizer.llm_call_tools_counter == 0


class TestHistoryManagement:
    """Tests for history management methods."""

    def test_history_starts_empty(self) -> None:
        """History should start empty."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        assert optimizer.get_history_entries() == []

    def test_round_lifecycle_adds_round_data(self, simple_chat_prompt) -> None:
        """start/record/end round should add round data via the history state."""
        optimizer = ConcreteOptimizer(model="gpt-4")
        context = MagicMock()

        handle = optimizer.pre_round(context)
        optimizer.post_trial(
            context,
            simple_chat_prompt,
            score=0.5,
            trial_index=1,
            round_handle=handle,
        )
        optimizer.post_round(
            round_handle=handle,
            best_score=0.5,
            best_candidate=simple_chat_prompt,
            extras={"improvement": 0.0},
        )

        history = optimizer.get_history_entries()
        assert len(history) == 1
        assert history[0]["round_index"] == 0

    def test_cleanup_clears_history(self, simple_chat_prompt) -> None:
        """cleanup should clear the history."""
        optimizer = ConcreteOptimizer(model="gpt-4")
        context = MagicMock()

        handle = optimizer.pre_round(context)
        optimizer.post_trial(
            context,
            simple_chat_prompt,
            score=0.5,
            trial_index=1,
            round_handle=handle,
        )
        optimizer.post_round(
            round_handle=handle,
            best_score=0.5,
            best_candidate=simple_chat_prompt,
            extras={"improvement": 0.0},
        )

        optimizer.cleanup()

        assert optimizer.get_history_entries() == []


def test_pre_trial_invoked_during_evaluate(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    class TrialSpyOptimizer(ConcreteOptimizer):
        def __init__(self) -> None:
            super().__init__(model="gpt-4")
            self.pre_trial_called = False

        def pre_trial(
            self, context: OptimizationContext, candidate: Any, round_handle=None
        ):
            self.pre_trial_called = True
            return candidate

    optimizer = TrialSpyOptimizer()
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

    monkeypatch.setattr(
        optimizer,
        "evaluate_prompt",
        lambda **kwargs: 0.5,
    )

    optimizer.evaluate(context, {"main": simple_chat_prompt})
    assert optimizer.pre_trial_called is True


def test_on_trial_called_after_evaluation(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    class TrialSpyOptimizer(ConcreteOptimizer):
        def __init__(self) -> None:
            super().__init__(model="gpt-4")
            self.on_trial_called = False

        def on_trial(
            self,
            context: OptimizationContext,
            prompts: dict[str, chat_prompt.ChatPrompt],
            score: float,
            prev_best_score: float | None = None,
        ) -> None:
            self.on_trial_called = True

    optimizer = TrialSpyOptimizer()
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

    monkeypatch.setattr(
        optimizer,
        "evaluate_prompt",
        lambda **kwargs: 0.5,
    )

    optimizer.evaluate(context, {"main": simple_chat_prompt})
    assert optimizer.on_trial_called is True


def test_post_trial_not_called_by_evaluate(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    class TrialSpyOptimizer(ConcreteOptimizer):
        def __init__(self) -> None:
            super().__init__(model="gpt-4")
            self.post_trial_called = False

        def post_trial(
            self,
            context: OptimizationContext,
            candidate_handle: Any,
            *,
            score: float | None,
            **kwargs: Any,
        ) -> None:
            self.post_trial_called = True

    optimizer = TrialSpyOptimizer()
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

    monkeypatch.setattr(
        optimizer,
        "evaluate_prompt",
        lambda **kwargs: 0.5,
    )

    optimizer.evaluate(context, {"main": simple_chat_prompt})
    assert optimizer.post_trial_called is False


class TestCleanup:
    """Tests for cleanup method."""

    def test_cleanup_resets_counters(self) -> None:
        """cleanup should reset call counters."""
        optimizer = ConcreteOptimizer(model="gpt-4")
        optimizer._increment_llm_counter()
        optimizer._increment_llm_call_tools_counter()

        optimizer.cleanup()

        assert optimizer.llm_call_counter == 0
        assert optimizer.llm_call_tools_counter == 0

    def test_cleanup_clears_opik_client(self) -> None:
        """cleanup should clear the Opik client reference."""
        optimizer = ConcreteOptimizer(model="gpt-4")
        optimizer._opik_client = cast(Any, MagicMock())

        optimizer.cleanup()

        assert optimizer._opik_client is None


class TestOptimizerInitialization:
    """Tests for optimizer initialization."""

    @pytest.mark.parametrize(
        "kwargs,expected,env_cleanup",
        [
            (
                {"model": "gpt-4"},
                {
                    "model": "gpt-4",
                    "verbose": 1,
                    "seed": 42,
                    "model_parameters": {},
                    "name": None,
                    "project_name": "Optimization",
                },
                True,
            ),
            (
                {
                    "model": "claude-3",
                    "verbose": 0,
                    "seed": 123,
                    "model_parameters": {"temperature": 0.7},
                    "name": "my-optimizer",
                },
                {
                    "model": "claude-3",
                    "verbose": 0,
                    "seed": 123,
                    "model_parameters": {"temperature": 0.7},
                    "name": "my-optimizer",
                },
                False,
            ),
        ],
    )
    def test_initialization(
        self,
        monkeypatch: pytest.MonkeyPatch,
        kwargs: dict[str, Any],
        expected: dict[str, Any],
        env_cleanup: bool,
    ) -> None:
        """Should set default and custom values correctly."""
        if env_cleanup:
            monkeypatch.delenv("OPIK_PROJECT_NAME", raising=False)
        optimizer = ConcreteOptimizer(**kwargs)
        for key, value in expected.items():
            assert getattr(optimizer, key) == value

    def test_reasoning_model_set_to_model(self) -> None:
        """reasoning_model should be set to the same value as model."""
        optimizer = ConcreteOptimizer(model="gpt-4o")

        assert optimizer.reasoning_model == "gpt-4o"


class TestDescribeAnnotation:
    """Tests for utils.tool_helpers.describe_annotation."""

    def test_returns_none_for_empty_annotation(self) -> None:
        """Should return None for inspect._empty."""
        import inspect

        result = tool_utils.describe_annotation(inspect._empty)

        assert result is None

    def test_returns_name_for_type(self) -> None:
        """Should return __name__ for type objects."""
        result = tool_utils.describe_annotation(str)

        assert result == "str"

    def test_returns_string_for_other(self) -> None:
        """Should return string representation for other objects."""
        result = tool_utils.describe_annotation("custom_annotation")

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

        mock_ds = make_mock_dataset(name="test-dataset")

        def metric(dataset_item, llm_output):
            return 1.0

        result = optimizer._create_optimization_run(mock_ds, metric)

        assert result is not None
        assert optimizer.current_optimization_id == "opt-123"

    def test_returns_none_on_error(self, optimizer, mock_opik_client) -> None:
        mock_client = mock_opik_client()
        mock_client.create_optimization.side_effect = Exception("API error")

        mock_ds = make_mock_dataset(name="test-dataset")

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
        training_ds = make_mock_dataset(name="training")

        result = optimizer._select_evaluation_dataset(training_ds, None)

        assert result is training_ds

    def test_returns_validation_dataset_when_provided(self, optimizer) -> None:
        training_ds = make_mock_dataset(name="training")
        validation_ds = make_mock_dataset(name="validation")

        result = optimizer._select_evaluation_dataset(training_ds, validation_ds)

        assert result is validation_ds

    def test_returns_training_when_warn_unsupported_set(self, optimizer) -> None:
        """When warn_unsupported=True, validation_dataset is ignored and training is returned."""
        training_ds = make_mock_dataset(name="training")
        validation_ds = make_mock_dataset(name="validation")

        result = optimizer._select_evaluation_dataset(
            training_ds, validation_ds, warn_unsupported=True
        )

        # When warn_unsupported=True, the warning says validation is ignored,
        # so training dataset should be returned
        assert result is training_ds


class TestToolUseFlag:
    def test_evaluate_prompt_passes_allow_tool_use(
        self, monkeypatch: pytest.MonkeyPatch
    ):
        optimizer = ConcreteOptimizer(model="gpt-4")
        agent = _ToolFlagAgent()
        dataset = make_mock_dataset()

        def assert_output(output: dict[str, Any]) -> None:
            assert agent.last_allow_tool_use is True

        monkeypatch.setattr(
            "opik_optimizer.core.evaluation.evaluate",
            make_fake_evaluator(assert_output=assert_output),
        )

        prompt = ChatPrompt(system="Test", user="Query")
        optimizer.evaluate_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=lambda *_: 1.0,
            agent=agent,
            allow_tool_use=True,
        )

    def test_evaluate_prompt_defaults_tool_use(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        optimizer = ConcreteOptimizer(model="gpt-4")
        agent = _ToolFlagAgent()
        dataset = make_mock_dataset()

        def assert_output(output: dict[str, Any]) -> None:
            assert agent.last_allow_tool_use is True

        monkeypatch.setattr(
            "opik_optimizer.core.evaluation.evaluate",
            make_fake_evaluator(assert_output=assert_output),
        )

        prompt = ChatPrompt(system="Test", user="Query")
        optimizer.evaluate_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=lambda *_: 1.0,
            agent=agent,
        )

    def test_setup_optimization_sets_allow_tool_use(self) -> None:
        optimizer = ConcreteOptimizer(model="gpt-4")
        dataset = make_mock_dataset()
        prompt = ChatPrompt(system="Test", user="Query")
        context = optimizer._setup_optimization(
            prompt=prompt,
            dataset=dataset,
            metric=lambda *_: 1.0,
            allow_tool_use=False,
        )
        assert context.allow_tool_use is False


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
        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

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
        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

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
        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

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
        from opik_optimizer.agents import LiteLLMAgent

        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

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
        from opik_optimizer.agents import OptimizableAgent

        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )
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
        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

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
        context = make_optimization_context(
            ChatPrompt(name="test", system="test", user="test"),
            optimization=mock_optimization,
            optimization_id="opt-123",
        )

        optimizer._finalize_optimization(context, status="completed")

        mock_optimization.update.assert_called_with(status="completed")

    def test_handles_none_optimization(self, optimizer) -> None:
        """Should not raise when optimization is None."""
        context = make_optimization_context(
            ChatPrompt(name="test", system="test", user="test"),
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
        return make_optimization_context(
            ChatPrompt(name="test", system="test", user="test"),
        )

    @pytest.mark.parametrize(
        "trials_completed,max_trials,initial_finish_reason,expected",
        [
            (10, 10, None, "max_trials"),  # Equal to limit - key bug test
            (15, 10, None, "max_trials"),  # Over limit
            (5, 10, None, "completed"),  # Under limit
            (0, 10, None, "completed"),  # Zero trials
            (0, 0, None, "max_trials"),  # Edge case: both zero
            (10, 10, "perfect_score", "perfect_score"),  # Preserve early stop
        ],
    )
    def test_finish_reason_logic(
        self,
        optimizer: ConcreteOptimizer,
        base_context: OptimizationContext,
        trials_completed: int,
        max_trials: int,
        initial_finish_reason: str | None,
        expected: str,
    ) -> None:
        """Test finish_reason logic for various scenarios.

        Key bug test: When a while loop exits because trials_completed >= max_trials,
        the code after the loop would unconditionally set finish_reason = "completed"
        instead of checking if max_trials was reached.
        """
        base_context.trials_completed = trials_completed
        base_context.max_trials = max_trials
        base_context.finish_reason = initial_finish_reason  # type: ignore[assignment]

        optimizer._finalize_finish_reason(base_context)

        assert base_context.finish_reason == expected

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
        dataset = make_mock_dataset(dataset_id="ds-123")
        metric = MagicMock(__name__="test_metric")
        return make_optimization_context(
            simple_chat_prompt,
            dataset=dataset,
            metric=metric,
            optimization_id="opt-123",
            baseline_score=0.5,
        )

    @pytest.mark.parametrize(
        "finish_reason,trials_completed,expected_stopped,expected_reason",
        [
            ("max_trials", 10, True, "max_trials"),
            ("completed", 5, False, "completed"),
            ("perfect_score", 3, True, "perfect_score"),
            ("no_improvement", 7, True, "no_improvement"),
        ],
    )
    def test_stopped_early_logic(
        self,
        optimizer: ConcreteOptimizer,
        base_context: OptimizationContext,
        simple_chat_prompt: ChatPrompt,
        finish_reason: str,
        trials_completed: int,
        expected_stopped: bool,
        expected_reason: str,
    ) -> None:
        """Test stopped_early and stop_reason logic for various finish reasons."""
        from opik_optimizer.core.state import AlgorithmResult

        base_context.finish_reason = finish_reason  # type: ignore[assignment]
        base_context.trials_completed = trials_completed

        algorithm_result = AlgorithmResult(
            best_prompts={"main": simple_chat_prompt},
            best_score=0.8,
            history=[],
            metadata={},
        )

        result = optimizer._build_final_result(algorithm_result, base_context)

        assert result.details["stopped_early"] is expected_stopped
        assert result.details["stop_reason"] == expected_reason
        if finish_reason == "max_trials":
            assert result.details["finish_reason"] == expected_reason


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
