# mypy: disable-error-code=no-untyped-def

import contextlib
import copy
from collections.abc import Callable
from typing import Any, no_type_check
from unittest.mock import MagicMock

from datetime import datetime, timezone
import pytest
from optuna.trial import TrialState

from opik_optimizer import ChatPrompt, ParameterOptimizer
from opik_optimizer.algorithms.parameter_optimizer.ops.search_ops import (
    ParameterSearchSpace,
    ParameterSpec,
)
from opik_optimizer.algorithms.parameter_optimizer.ops.optuna_ops import (
    build_optuna_objective,
)
from opik_optimizer.algorithms.parameter_optimizer.types import ParameterType
from tests.unit.test_helpers import (
    make_mock_dataset,
    make_simple_metric,
    STANDARD_DATASET_ITEMS,
)
from tests.unit.fixtures import assert_baseline_early_stop
from tests.unit.fixtures import make_baseline_prompt


def _float_param(
    name: str,
    *,
    low: float = 0.0,
    high: float = 1.0,
    **kwargs: Any,
) -> ParameterSpec:
    return ParameterSpec(
        name=name,
        distribution=ParameterType.FLOAT,
        low=low,
        high=high,
        **kwargs,
    )


def _categorical_param(name: str, *, choices: list[str]) -> ParameterSpec:
    return ParameterSpec(
        name=name,
        distribution=ParameterType.CATEGORICAL,
        choices=choices,
    )


def _space(*parameters: ParameterSpec) -> ParameterSearchSpace:
    return ParameterSearchSpace(parameters=list(parameters))


class TestParameterOptimizerInit:
    @pytest.mark.parametrize(
        "kwargs,expected",
        [
            ({"model": "gpt-4o"}, {"model": "gpt-4o", "seed": 42}),
            (
                {"model": "gpt-4o-mini", "verbose": 0, "seed": 123},
                {"model": "gpt-4o-mini", "verbose": 0, "seed": 123},
            ),
        ],
    )
    def test_initialization(
        self, kwargs: dict[str, Any], expected: dict[str, Any]
    ) -> None:
        """Test optimizer initialization with defaults and custom params."""
        optimizer = ParameterOptimizer(**kwargs)
        for key, value in expected.items():
            assert getattr(optimizer, key) == value


class TestParameterOptimizerOptimizePrompt:
    def test_optimize_prompt_raises_not_implemented(
        self,
        mock_optimization_context,
    ) -> None:
        mock_optimization_context()
        optimizer = ParameterOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompt = ChatPrompt(system="Test", user="{question}")
        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )

        with pytest.raises(NotImplementedError):
            optimizer.optimize_prompt(
                prompt=prompt,
                dataset=dataset,
                metric=make_simple_metric(),
                max_trials=1,
            )


class TestParameterOptimizerEarlyStop:
    def test_skips_on_perfect_score(
        self,
        mock_opik_client: Callable[..., MagicMock],
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        mock_opik_client()
        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )
        optimizer = ParameterOptimizer(model="gpt-4o", perfect_score=0.95)

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)
        monkeypatch.setattr(
            "optuna.create_study",
            lambda **kwargs: (_ for _ in ()).throw(AssertionError("should not run")),
        )

        prompt = make_baseline_prompt()
        parameter_space = _space(_float_param("temperature"))
        result = optimizer.optimize_parameter(
            prompt=prompt,
            dataset=dataset,
            metric=make_simple_metric(),
            parameter_space=parameter_space,
            max_trials=1,
        )

        assert_baseline_early_stop(result, perfect_score=0.95)
        assert result.details["n_trials"] == 0


class TestExpandForPrompts:
    """Tests for ParameterSearchSpace.expand_for_prompts()."""

    def test_expands_unprefixed_params(self) -> None:
        """Should expand parameters without prefix for each prompt."""
        space = _space(_float_param("temperature"))

        expanded = space.expand_for_prompts(["analyze", "respond"])

        assert len(expanded.parameters) == 2
        names = [p.name for p in expanded.parameters]
        assert "analyze.temperature" in names
        assert "respond.temperature" in names

    def test_preserves_already_prefixed_params(self) -> None:
        """Should keep params that already have a prompt prefix."""
        space = _space(_float_param("analyze.temperature"))

        expanded = space.expand_for_prompts(["analyze", "respond"])

        assert len(expanded.parameters) == 1
        assert expanded.parameters[0].name == "analyze.temperature"

    def test_handles_mixed_prefixed_and_unprefixed(self) -> None:
        """Should handle mix of prefixed and unprefixed parameters."""
        space = _space(
            _float_param("temperature"),
            _float_param("analyze.top_p", low=0.1, high=1.0),
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
        space = _space(
            _float_param("temperature"),
            _float_param("top_p", low=0.1, high=1.0),
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
        space = _space(_float_param("temperature"))

        expanded = space.expand_for_prompts(["my_prompt"])

        assert len(expanded.parameters) == 1
        assert expanded.parameters[0].name == "my_prompt.temperature"

    def test_preserves_spec_properties(self) -> None:
        """Should preserve all spec properties when expanding."""
        space = _space(
            _float_param("temperature", low=0.1, high=2.0, step=0.1, scale="log")
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
        space = _space(_categorical_param("model", choices=["gpt-4o", "gpt-4o-mini"]))

        expanded = space.expand_for_prompts(["p1", "p2"])

        assert len(expanded.parameters) == 2
        for spec in expanded.parameters:
            assert spec.distribution == ParameterType.CATEGORICAL
            assert spec.choices == ["gpt-4o", "gpt-4o-mini"]


class TestApplyToPrompts:
    """Tests for ParameterSearchSpace.apply_to_prompts()."""

    def test_applies_prefixed_values_to_correct_prompts(self) -> None:
        """Should apply values with correct prefix to each prompt."""
        space = _space(
            _float_param("analyze.temperature"),
            _float_param("respond.temperature"),
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
        space = _space(_float_param("p.temperature"))

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
        dataset = make_mock_dataset(
            [{"id": "1", "question": "Q1", "answer": "A1"}],
            name="train",
            dataset_id="ds-1",
        )

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
            "opik_optimizer.algorithms.parameter_optimizer.ops.optuna_ops.reporting.display_trial_evaluation",
            fake_trial_reporter,
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.parameter_optimizer.ops.optuna_ops.optuna_importance.get_param_importances",
            lambda study, params=None, target=None: {},
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.parameter_optimizer.ops.optuna_ops.sensitivity_analysis",
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


class TestOptunaHistoryRedaction:
    def _run_objective(
        self,
        *,
        monkeypatch: pytest.MonkeyPatch,
    ) -> dict[str, Any]:
        dataset = make_mock_dataset(
            [{"id": "1", "question": "Q1", "answer": "A1"}],
            name="train",
            dataset_id="ds-1",
        )
        prompt = ChatPrompt(name="p", system="Test")
        optimizer = ParameterOptimizer(model="gpt-4o", verbose=0)

        (
            context,
            base_prompts,
            base_model_kwargs,
            expanded_parameter_space,
            _,
            evaluation_dataset,
            experiment_config,
        ) = optimizer._prepare_parameter_context(
            prompt=prompt,
            dataset=dataset,
            metric=lambda item, output: 0.5,
            parameter_space=ParameterSearchSpace(
                parameters=[
                    ParameterSpec(
                        name="api_key",
                        distribution=ParameterType.FLOAT,
                        low=0.0,
                        high=1.0,
                    )
                ]
            ),
            validation_dataset=None,
            experiment_config=None,
            n_samples=None,
            agent=None,
            project_name="Optimization",
            optimization_id=None,
            max_trials=1,
        )

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        @contextlib.contextmanager
        def fake_trial_reporter(**kwargs):
            reporter = MagicMock()
            yield reporter

        monkeypatch.setattr(
            "opik_optimizer.algorithms.parameter_optimizer.ops.optuna_ops.reporting.display_trial_evaluation",
            fake_trial_reporter,
        )

        captured: dict[str, Any] = {}

        def fake_record_and_post_trial(**kwargs: Any) -> None:
            captured.update(kwargs)

        monkeypatch.setattr(
            "opik_optimizer.algorithms.parameter_optimizer.ops.optuna_ops.runtime.record_and_post_trial",
            fake_record_and_post_trial,
        )
        monkeypatch.setattr(optimizer, "post_round", lambda *args, **kwargs: None)

        current_space_ref = {"space": expanded_parameter_space}
        stage_ref: dict[str, Any] = {"name": "global"}
        stage_counts: dict[str, int] = {}
        best_state = {"score": 0.0, "prompts": copy.deepcopy(base_prompts)}

        objective = build_optuna_objective(
            optimizer=optimizer,
            context=context,
            current_space_ref=current_space_ref,
            stage_ref=stage_ref,
            stage_counts=stage_counts,
            best_state=best_state,
            base_prompts=base_prompts,
            base_model_kwargs=base_model_kwargs,
            evaluation_dataset=evaluation_dataset,
            metric=lambda item, output: 0.5,
            agent=context.agent,
            experiment_config=experiment_config,
            n_samples=None,
            total_trials=1,
        )

        class FakeTrial:
            def __init__(self) -> None:
                self.number = 0
                self.user_attrs: dict[str, Any] = {}

            def set_user_attr(self, key: str, value: Any) -> None:
                self.user_attrs[key] = value

            def suggest_float(
                self, name: str, low: float, high: float, step=None, log=False
            ) -> float:
                _ = (name, step, log)
                return (low + high) / 2

        objective(FakeTrial())
        return captured

    def test_redacts_sensitive_history_by_default(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        captured = self._run_objective(monkeypatch=monkeypatch)
        prompt_payload = captured["prompt_or_payload"]
        assert isinstance(prompt_payload, dict)
        assert isinstance(prompt_payload["p"], ChatPrompt)
        params = captured["extra"]["parameters"]
        assert params.get("p.api_key") == "<REDACTED>"
