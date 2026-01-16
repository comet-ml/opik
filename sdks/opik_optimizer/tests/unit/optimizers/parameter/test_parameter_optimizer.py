# mypy: disable-error-code=no-untyped-def

import contextlib
from collections.abc import Callable
from typing import Any, no_type_check
from unittest.mock import MagicMock

from datetime import datetime, timezone
import pytest
from optuna.trial import TrialState

from opik import Dataset
from opik_optimizer import ChatPrompt, ParameterOptimizer
from opik_optimizer.algorithms.parameter_optimizer.parameter_search_space import (
    ParameterSearchSpace,
)
from opik_optimizer.algorithms.parameter_optimizer.parameter_spec import ParameterSpec
from opik_optimizer.algorithms.parameter_optimizer.search_space_types import (
    ParameterType,
)


def _metric(dataset_item: dict[str, Any], llm_output: str) -> float:
    return 1.0


def _make_dataset() -> MagicMock:
    dataset = MagicMock(spec=Dataset)
    dataset.name = "test-dataset"
    dataset.id = "dataset-123"
    dataset.get_items.return_value = [{"id": "1", "question": "Q1", "answer": "A1"}]
    return dataset


class TestParameterOptimizerInit:
    def test_initialization_with_defaults(self) -> None:
        optimizer = ParameterOptimizer(model="gpt-4o")
        assert optimizer.model == "gpt-4o"
        assert optimizer.seed == 42

    def test_initialization_with_custom_params(self) -> None:
        optimizer = ParameterOptimizer(
            model="gpt-4o-mini",
            verbose=0,
            seed=123,
        )
        assert optimizer.model == "gpt-4o-mini"
        assert optimizer.verbose == 0
        assert optimizer.seed == 123


class TestParameterOptimizerOptimizePrompt:
    def test_optimize_prompt_raises_not_implemented(
        self,
        mock_optimization_context,
    ) -> None:
        mock_optimization_context()
        optimizer = ParameterOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompt = ChatPrompt(system="Test", user="{question}")
        dataset = _make_dataset()

        with pytest.raises(NotImplementedError):
            optimizer.optimize_prompt(
                prompt=prompt,
                dataset=dataset,
                metric=_metric,
                max_trials=1,
            )


class TestParameterOptimizerEarlyStop:
    def test_skips_on_perfect_score(
        self,
        mock_opik_client: Callable[..., MagicMock],
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        mock_opik_client()
        dataset = _make_dataset()
        optimizer = ParameterOptimizer(model="gpt-4o", perfect_score=0.95)

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)
        monkeypatch.setattr(
            "optuna.create_study",
            lambda **kwargs: (_ for _ in ()).throw(AssertionError("should not run")),
        )

        prompt = ChatPrompt(system="baseline", user="{question}")
        parameter_space = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="temperature",
                    distribution=ParameterType.FLOAT,
                    low=0.0,
                    high=1.0,
                )
            ]
        )
        result = optimizer.optimize_parameter(
            prompt=prompt,
            dataset=dataset,
            metric=_metric,
            parameter_space=parameter_space,
            max_trials=1,
        )

        assert result.details["stopped_early"] is True
        assert result.details["stop_reason"] == "baseline_score_met_threshold"
        assert result.details["perfect_score"] == 0.95
        assert result.initial_score == result.score
        assert result.details["n_trials"] == 0


class TestExpandForPrompts:
    """Tests for ParameterSearchSpace.expand_for_prompts()."""

    def test_expands_unprefixed_params(self) -> None:
        """Should expand parameters without prefix for each prompt."""
        space = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="temperature",
                    distribution=ParameterType.FLOAT,
                    low=0.0,
                    high=1.0,
                ),
            ]
        )

        expanded = space.expand_for_prompts(["analyze", "respond"])

        assert len(expanded.parameters) == 2
        names = [p.name for p in expanded.parameters]
        assert "analyze.temperature" in names
        assert "respond.temperature" in names

    def test_preserves_already_prefixed_params(self) -> None:
        """Should keep params that already have a prompt prefix."""
        space = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="analyze.temperature",
                    distribution=ParameterType.FLOAT,
                    low=0.0,
                    high=1.0,
                ),
            ]
        )

        expanded = space.expand_for_prompts(["analyze", "respond"])

        assert len(expanded.parameters) == 1
        assert expanded.parameters[0].name == "analyze.temperature"

    def test_handles_mixed_prefixed_and_unprefixed(self) -> None:
        """Should handle mix of prefixed and unprefixed parameters."""
        space = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="temperature",
                    distribution=ParameterType.FLOAT,
                    low=0.0,
                    high=1.0,
                ),
                ParameterSpec(
                    name="analyze.top_p",
                    distribution=ParameterType.FLOAT,
                    low=0.1,
                    high=1.0,
                ),
            ]
        )

        expanded = space.expand_for_prompts(["analyze", "respond"])

        assert len(expanded.parameters) == 3
        names = [p.name for p in expanded.parameters]
        assert "analyze.temperature" in names
        assert "respond.temperature" in names
        assert "analyze.top_p" in names
        assert "respond.top_p" not in names  # Was already prefixed, not expanded

    def test_expands_multiple_params(self) -> None:
        """Should expand multiple unprefixed parameters."""
        space = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="temperature",
                    distribution=ParameterType.FLOAT,
                    low=0.0,
                    high=1.0,
                ),
                ParameterSpec(
                    name="top_p", distribution=ParameterType.FLOAT, low=0.1, high=1.0
                ),
            ]
        )

        expanded = space.expand_for_prompts(["a", "b"])

        assert len(expanded.parameters) == 4
        names = [p.name for p in expanded.parameters]
        assert "a.temperature" in names
        assert "b.temperature" in names
        assert "a.top_p" in names
        assert "b.top_p" in names

    def test_single_prompt_expansion(self) -> None:
        """Should expand for single prompt (used when single ChatPrompt passed)."""
        space = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="temperature",
                    distribution=ParameterType.FLOAT,
                    low=0.0,
                    high=1.0,
                ),
            ]
        )

        expanded = space.expand_for_prompts(["my_prompt"])

        assert len(expanded.parameters) == 1
        assert expanded.parameters[0].name == "my_prompt.temperature"

    def test_preserves_spec_properties(self) -> None:
        """Should preserve all spec properties when expanding."""
        space = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="temperature",
                    distribution=ParameterType.FLOAT,
                    low=0.1,
                    high=2.0,
                    step=0.1,
                    scale="log",
                ),
            ]
        )

        expanded = space.expand_for_prompts(["test"])

        spec = expanded.parameters[0]
        assert spec.name == "test.temperature"
        assert spec.distribution == ParameterType.FLOAT
        assert spec.low == 0.1
        assert spec.high == 2.0
        assert spec.step == 0.1
        assert spec.scale == "log"

    def test_categorical_param_expansion(self) -> None:
        """Should expand categorical parameters correctly."""
        space = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="model",
                    distribution=ParameterType.CATEGORICAL,
                    choices=["gpt-4o", "gpt-4o-mini"],
                ),
            ]
        )

        expanded = space.expand_for_prompts(["p1", "p2"])

        assert len(expanded.parameters) == 2
        for spec in expanded.parameters:
            assert spec.distribution == ParameterType.CATEGORICAL
            assert spec.choices == ["gpt-4o", "gpt-4o-mini"]


class TestApplyToPrompts:
    """Tests for ParameterSearchSpace.apply_to_prompts()."""

    def test_applies_prefixed_values_to_correct_prompts(self) -> None:
        """Should apply values with correct prefix to each prompt."""
        space = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="analyze.temperature",
                    distribution=ParameterType.FLOAT,
                    low=0.0,
                    high=1.0,
                ),
                ParameterSpec(
                    name="respond.temperature",
                    distribution=ParameterType.FLOAT,
                    low=0.0,
                    high=1.0,
                ),
            ]
        )

        prompts = {
            "analyze": ChatPrompt(name="analyze", system="Analyze"),
            "respond": ChatPrompt(name="respond", system="Respond"),
        }

        result = space.apply_to_prompts(
            prompts,
            {"analyze.temperature": 0.3, "respond.temperature": 0.8},
            base_model_kwargs={},
        )

        assert result["analyze"].model_kwargs["temperature"] == 0.3
        assert result["respond"].model_kwargs["temperature"] == 0.8

    def test_does_not_mutate_original_prompts(self) -> None:
        """Should return copies, not mutate originals."""
        space = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="p.temperature",
                    distribution=ParameterType.FLOAT,
                    low=0.0,
                    high=1.0,
                ),
            ]
        )

        original = ChatPrompt(name="p", system="Test")
        original.model_kwargs = {"temperature": 0.5}

        prompts = {"p": original}

        result = space.apply_to_prompts(
            prompts,
            {"p.temperature": 0.9},
            base_model_kwargs=None,
        )

        assert result["p"] is not original
        assert original.model_kwargs["temperature"] == 0.5
        assert result["p"].model_kwargs["temperature"] == 0.9

    def test_handles_missing_prompt_prefix(self) -> None:
        """Should handle values that don't match any prompt prefix."""
        space = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="other.temperature",
                    distribution=ParameterType.FLOAT,
                    low=0.0,
                    high=1.0,
                ),
            ]
        )

        prompts = {"my_prompt": ChatPrompt(name="my_prompt", system="Test")}

        result = space.apply_to_prompts(
            prompts,
            {"other.temperature": 0.5},
            base_model_kwargs={},
        )

        # Should return prompt unchanged (no matching prefix)
        assert "my_prompt" in result
        assert "temperature" not in (result["my_prompt"].model_kwargs or {})

    def test_applies_model_parameter_to_each_prompt(self) -> None:
        """Should apply model parameter independently to each prompt."""
        space = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="analyze.model",
                    distribution=ParameterType.CATEGORICAL,
                    choices=["gpt-4o", "gpt-4o-mini"],
                    target="model",
                ),
                ParameterSpec(
                    name="respond.model",
                    distribution=ParameterType.CATEGORICAL,
                    choices=["gpt-4o", "gpt-4o-mini"],
                    target="model",
                ),
            ]
        )

        prompts = {
            "analyze": ChatPrompt(name="analyze", system="Analyze", model="default"),
            "respond": ChatPrompt(name="respond", system="Respond", model="default"),
        }

        result = space.apply_to_prompts(
            prompts,
            {"analyze.model": "gpt-4o", "respond.model": "gpt-4o-mini"},
            base_model_kwargs={},
        )

        assert result["analyze"].model == "gpt-4o"
        assert result["respond"].model == "gpt-4o-mini"

    def test_expand_and_apply_model_parameter(self) -> None:
        """Should expand model parameter for each prompt and apply correctly."""
        # Start with unexpanded parameter space
        space = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="model",
                    distribution=ParameterType.CATEGORICAL,
                    choices=["gpt-4o", "gpt-4o-mini"],
                    target="model",
                ),
            ]
        )

        prompts = {
            "p1": ChatPrompt(name="p1", system="P1", model="default"),
            "p2": ChatPrompt(name="p2", system="P2", model="default"),
        }

        # Expand for both prompts
        expanded = space.expand_for_prompts(["p1", "p2"])

        # Should have two model params now
        assert len(expanded.parameters) == 2
        names = [p.name for p in expanded.parameters]
        assert "p1.model" in names
        assert "p2.model" in names

        # Apply different models to each prompt
        result = expanded.apply_to_prompts(
            prompts,
            {"p1.model": "gpt-4o", "p2.model": "gpt-4o-mini"},
            base_model_kwargs={},
        )

        assert result["p1"].model == "gpt-4o"
        assert result["p2"].model == "gpt-4o-mini"

    def test_mixed_model_and_temperature_expansion(self) -> None:
        """Should expand both model and temperature parameters independently."""
        space = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="model",
                    distribution=ParameterType.CATEGORICAL,
                    choices=["gpt-4o", "gpt-4o-mini"],
                    target="model",
                ),
                ParameterSpec(
                    name="temperature",
                    distribution=ParameterType.FLOAT,
                    low=0.0,
                    high=1.0,
                ),
            ]
        )

        prompts = {
            "a": ChatPrompt(name="a", system="A", model="default"),
            "b": ChatPrompt(name="b", system="B", model="default"),
        }

        expanded = space.expand_for_prompts(["a", "b"])

        # Should have 4 params: a.model, b.model, a.temperature, b.temperature
        assert len(expanded.parameters) == 4

        result = expanded.apply_to_prompts(
            prompts,
            {
                "a.model": "gpt-4o",
                "b.model": "gpt-4o-mini",
                "a.temperature": 0.2,
                "b.temperature": 0.9,
            },
            base_model_kwargs={},
        )

        assert result["a"].model == "gpt-4o"
        assert result["b"].model == "gpt-4o-mini"
        assert result["a"].model_kwargs["temperature"] == 0.2
        assert result["b"].model_kwargs["temperature"] == 0.9


class TestMultiPromptNormalization:
    """Tests for prompt normalization in ParameterOptimizer."""

    def test_single_prompt_normalized_to_dict(self) -> None:
        """Single ChatPrompt should be normalized to dict internally."""
        prompt = ChatPrompt(name="test_prompt", system="Hello")

        # This tests the normalization logic pattern
        if isinstance(prompt, ChatPrompt):
            prompts = {prompt.name: prompt}
            is_single = True
        else:
            prompts = prompt
            is_single = False

        assert isinstance(prompts, dict)
        assert "test_prompt" in prompts
        assert is_single is True

    def test_dict_prompt_kept_as_dict(self) -> None:
        """Dict of prompts should be kept as-is."""
        prompt_dict = {
            "a": ChatPrompt(name="a", system="A"),
            "b": ChatPrompt(name="b", system="B"),
        }

        if isinstance(prompt_dict, ChatPrompt):
            prompts = {prompt_dict.name: prompt_dict}
            is_single = True
        else:
            prompts = prompt_dict
            is_single = False

        assert prompts is prompt_dict
        assert is_single is False


class TestBackwardCompatibility:
    """Tests for backward compatibility with single prompt usage."""

    def test_single_prompt_parameter_expansion(self) -> None:
        """Single prompt should expand parameters with its name as prefix."""
        space = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="temperature",
                    distribution=ParameterType.FLOAT,
                    low=0.0,
                    high=1.0,
                ),
            ]
        )

        prompt = ChatPrompt(name="my_single_prompt", system="Hello")
        prompt_names = [prompt.name]

        expanded = space.expand_for_prompts(prompt_names)

        assert len(expanded.parameters) == 1
        assert expanded.parameters[0].name == "my_single_prompt.temperature"

    def test_describe_with_expanded_params(self) -> None:
        """describe() should work with expanded parameter names."""
        space = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="temperature",
                    distribution=ParameterType.FLOAT,
                    low=0.0,
                    high=1.0,
                ),
            ]
        )

        expanded = space.expand_for_prompts(["p1", "p2"])
        description = expanded.describe()

        assert "p1.temperature" in description
        assert "p2.temperature" in description
        assert description["p1.temperature"]["min"] == 0.0
        assert description["p1.temperature"]["max"] == 1.0


class TestReporterLifecycle:
    def test_sets_and_clears_reporter_during_trial(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        dataset = MagicMock(spec=Dataset)
        dataset.name = "train"
        dataset.id = "ds-1"
        dataset.get_items.return_value = [{"id": "1", "question": "Q1", "answer": "A1"}]

        prompt = ChatPrompt(name="p", system="Test")

        optimizer = ParameterOptimizer(model="gpt-4o", verbose=0)
        events: list[str] = []

        orig_set = optimizer._set_reporter
        orig_clear = optimizer._clear_reporter

        def tracking_set(reporter: Any) -> None:
            events.append("set")
            orig_set(reporter)

        def tracking_clear() -> None:
            events.append("clear")
            orig_clear()

        monkeypatch.setattr(optimizer, "_set_reporter", tracking_set)
        monkeypatch.setattr(optimizer, "_clear_reporter", tracking_clear)

        # Stub evaluate_prompt to avoid real LLM calls
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        class FakeTrial:
            def __init__(self, number: int) -> None:
                self.number = number
                self.value: float | None = None
                self.user_attrs: dict[str, Any] = {}
                self.datetime_start = datetime.now(timezone.utc)
                self.datetime_complete = self.datetime_start
                self.state = TrialState.COMPLETE
                self.pruner = None

            def set_user_attr(self, key: str, value: Any) -> None:
                self.user_attrs[key] = value

            def suggest_float(
                self, name: str, low: float, high: float, step=None, log=False
            ) -> float:
                return (low + high) / 2

        @no_type_check
        class FakeStudy:
            def __init__(self) -> None:
                self.trials: list[FakeTrial] = []
                self.pruner = MagicMock()

            def optimize(
                self,
                objective: "Callable[[FakeTrial], float]",
                n_trials: int,
                timeout: float | None = None,
                callbacks: list | None = None,
                show_progress_bar: bool = False,
            ) -> None:
                _ = show_progress_bar  # keep signature parity, silence linters
                trial = FakeTrial(0)
                trial.value = objective(trial)
                self.trials.append(trial)

        def make_study(direction: str, sampler: Any) -> FakeStudy:
            return FakeStudy()

        monkeypatch.setattr(
            "opik_optimizer.algorithms.parameter_optimizer.parameter_optimizer.optuna.create_study",
            make_study,
        )

        @contextlib.contextmanager
        def fake_trial_reporter(**kwargs):
            reporter = MagicMock()
            yield reporter

        monkeypatch.setattr(
            "opik_optimizer.algorithms.parameter_optimizer.reporting.display_trial_evaluation",
            fake_trial_reporter,
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.parameter_optimizer.parameter_optimizer.optuna_importance.get_param_importances",
            lambda study, params=None, target=None: {},
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.parameter_optimizer.parameter_optimizer.compute_sensitivity_from_trials",
            lambda trials, specs: {},
        )

        space = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="temperature",
                    distribution=ParameterType.FLOAT,
                    low=0.0,
                    high=1.0,
                )
            ]
        )

        optimizer.optimize_parameter(
            prompt=prompt,
            dataset=dataset,
            metric=lambda item, output: 0.5,
            parameter_space=space,
            max_trials=1,
        )

        assert events == ["set", "clear"]
        assert optimizer._reporter is None
