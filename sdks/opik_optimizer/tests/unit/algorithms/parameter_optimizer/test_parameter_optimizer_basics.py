# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

import time
import warnings
from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import litellm
import pytest

from opik_optimizer import ChatPrompt, ParameterOptimizer
from tests.unit.algorithms.parameter_optimizer._test_builders import (
    categorical_param,
    float_param,
    space,
)
from tests.unit.fixtures import assert_baseline_early_stop, make_baseline_prompt
from tests.unit.test_helpers import (
    make_mock_dataset,
    make_simple_metric,
    STANDARD_DATASET_ITEMS,
)


class TestParameterOptimizerInit:
    @pytest.mark.parametrize(
        "kwargs,expected",
        [
            ({}, {"model": None, "model_parameters": {}, "seed": 42}),
            ({"verbose": 0, "seed": 123}, {"verbose": 0, "seed": 123}),
        ],
    )
    def test_initialization(
        self, kwargs: dict[str, Any], expected: dict[str, Any]
    ) -> None:
        with warnings.catch_warnings():
            warnings.simplefilter("error")
            optimizer = ParameterOptimizer(**kwargs)
        for key, value in expected.items():
            assert getattr(optimizer, key) == value

    @pytest.mark.parametrize(
        "kwargs",
        [
            {"model": "gpt-4o"},
            {"model_parameters": {"max_tokens": 64}},
        ],
    )
    def test_optimizer_model_settings_are_ignored_with_deprecation(
        self, kwargs: dict[str, Any]
    ) -> None:
        with pytest.warns(DeprecationWarning, match="on the ChatPrompt instead"):
            optimizer = ParameterOptimizer(**kwargs)
        assert optimizer.model is None
        assert optimizer.model_parameters == {}
        assert optimizer.reasoning_model is None


class TestModelInfoComesFromPrompts:
    def test_baseline_only_result_reports_prompt_models(
        self, mock_opik_client, monkeypatch
    ) -> None:
        client = mock_opik_client()
        monkeypatch.setattr(litellm, "get_supported_openai_params", lambda model: None)
        optimizer = ParameterOptimizer(verbose=0)
        seen = []

        def evaluate(**kwargs):
            seen.append(kwargs["prompt"])
            return 1.0

        monkeypatch.setattr(optimizer, "evaluate_prompt", evaluate)
        result = optimizer.optimize_parameter(
            prompt=ChatPrompt(name="p", system="A", model="agent-model"),
            dataset=make_mock_dataset(),
            metric=make_simple_metric(),
            parameter_space=space(float_param("temperature")),
        )
        assert seen[0]["p"].model == "agent-model"
        assert result.details["model"] == {"p": "agent-model"}
        assert "None" not in str(result)
        metadata = client.create_optimization.call_args.kwargs["metadata"]
        assert "model" not in metadata


class TestUnsupportedParameterWarning:
    @staticmethod
    def _supported(model: str) -> list[str] | None:
        if model == "raises":
            raise ValueError("unknown")
        return {"no-top-p": ["temperature", "max_tokens"]}.get(model)

    def _optimize(self, monkeypatch, prompt, parameters) -> None:
        monkeypatch.setattr(litellm, "get_supported_openai_params", self._supported)
        optimizer = ParameterOptimizer(verbose=0)
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 1.0)
        optimizer.optimize_parameter(
            prompt=prompt,
            dataset=make_mock_dataset(),
            metric=make_simple_metric(),
            parameter_space=space(*parameters),
        )

    def test_warns_once_per_parameter_and_model(
        self, mock_opik_client, monkeypatch
    ) -> None:
        mock_opik_client()
        prompts = {
            "a": ChatPrompt(name="a", system="A", model="no-top-p"),
            "b": ChatPrompt(name="b", system="B", model="no-top-p"),
        }
        with pytest.warns(UserWarning) as record:
            self._optimize(
                monkeypatch,
                prompts,
                [float_param("temperature"), float_param("top_p")],
            )
        messages = [str(w.message) for w in record if "not supported" in str(w.message)]
        assert len(messages) == 1
        assert "'top_p'" in messages[0] and "'no-top-p'" in messages[0]

    def test_checks_searched_model_choices(self, mock_opik_client, monkeypatch) -> None:
        mock_opik_client()
        with pytest.warns(
            UserWarning, match="'top_p' is not supported by model 'no-top-p'"
        ):
            self._optimize(
                monkeypatch,
                ChatPrompt(name="p", system="A", model="unknown-model"),
                [
                    float_param("top_p"),
                    categorical_param("model", choices=["no-top-p"], target="model"),
                ],
            )

    @pytest.mark.parametrize("model", ["unknown-model", "raises"])
    def test_no_warning_when_support_is_unknown(
        self, mock_opik_client, monkeypatch, model
    ) -> None:
        mock_opik_client()
        with warnings.catch_warnings(record=True) as record:
            warnings.simplefilter("always")
            self._optimize(
                monkeypatch,
                ChatPrompt(name="p", system="A", model=model),
                [float_param("top_p")],
            )
        assert not [w for w in record if "not supported" in str(w.message)]


class TestParameterOptimizerOptimizePrompt:
    def test_optimize_prompt_raises_not_implemented(
        self,
        mock_optimization_context,
    ) -> None:
        mock_optimization_context()
        optimizer = ParameterOptimizer(verbose=0, seed=42)
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
        optimizer = ParameterOptimizer(perfect_score=0.95)

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)
        monkeypatch.setattr(
            "optuna.create_study",
            lambda **kwargs: (_ for _ in ()).throw(AssertionError("should not run")),
        )

        prompt = make_baseline_prompt()
        parameter_space = space(float_param("temperature"))
        result = optimizer.optimize_parameter(
            prompt=prompt,
            dataset=dataset,
            metric=make_simple_metric(),
            parameter_space=parameter_space,
            max_trials=1,
        )

        assert_baseline_early_stop(result, perfect_score=0.95)
        assert result.details["n_trials"] == 0
        assert {
            "optimized_model",
            "optimized_model_kwargs",
            "parameter_space",
            "search_stages",
            "parameter_importance",
        } <= result.details.keys()
        # ParameterOptimizer bypasses runtime.build_final_result, so it must surface
        # scoring_health in its own result details (present, not dropped) — here the
        # float-mode baseline leaves it unset, so it falls back to the all-clear shape.
        assert result.details["scoring_health"] == {"failed_count": 0, "total_count": 0}


class TestNormalizePromptInput:
    def test_single_prompt_normalized_to_dict(self) -> None:
        optimizer = ParameterOptimizer(verbose=0)
        prompt = ChatPrompt(name="test_prompt", system="Hello")

        prompts, is_single = optimizer._normalize_prompt_input(prompt)

        assert isinstance(prompts, dict)
        assert "test_prompt" in prompts
        assert is_single is True

    def test_dict_prompt_kept_as_dict(self) -> None:
        optimizer = ParameterOptimizer(verbose=0)
        prompt_dict = {
            "a": ChatPrompt(name="a", system="A"),
            "b": ChatPrompt(name="b", system="B"),
        }

        prompts, is_single = optimizer._normalize_prompt_input(prompt_dict)

        assert prompts is prompt_dict
        assert is_single is False


class TestSharedLifecycle:
    def test_stops_on_winning_trial_and_keeps_prompt_models(
        self, mock_opik_client, monkeypatch
    ):
        mock_opik_client()
        optimizer = ParameterOptimizer(verbose=0)
        prompts = {
            "a": ChatPrompt(name="a", system="A", model="agent-a"),
            "b": ChatPrompt(name="b", system="B", model="agent-b"),
        }
        scores = iter([0.2, 1.0])
        seen = []

        def evaluate(**kwargs):
            seen.append(kwargs["prompt"])
            return next(scores)

        monkeypatch.setattr(optimizer, "evaluate_prompt", evaluate)
        finalized = MagicMock(wraps=optimizer._finalize_optimization)
        monkeypatch.setattr(optimizer, "_finalize_optimization", finalized)
        result = optimizer.optimize_parameter(
            prompt=prompts,
            dataset=make_mock_dataset(),
            metric=make_simple_metric(),
            parameter_space=space(float_param("temperature")),
            max_trials=5,
        )
        assert len(seen) == 2
        assert result.score == 1.0
        assert result.details["trials_completed"] == 1
        assert result.details["finish_reason"] == "perfect_score"
        assert {key: value.model for key, value in result.prompt.items()} == {
            "a": "agent-a",
            "b": "agent-b",
        }
        assert finalized.call_args.kwargs["status"] == "completed"

    def test_baseline_skip_finalizes(self, mock_opik_client, monkeypatch):
        mock_opik_client()
        optimizer = ParameterOptimizer(verbose=0)
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 1.0)
        finalized = MagicMock(wraps=optimizer._finalize_optimization)
        monkeypatch.setattr(optimizer, "_finalize_optimization", finalized)
        result = optimizer.optimize_parameter(
            prompt=ChatPrompt(system="A"),
            dataset=make_mock_dataset(),
            metric=make_simple_metric(),
            parameter_space=space(float_param("temperature")),
            max_trials=5,
        )
        assert result.details["finish_reason"] == "perfect_score"
        assert finalized.call_args.kwargs["status"] == "completed"


class TestFinishReasonAndWarnings:
    def test_timeout_before_trial_budget_reports_timeout(
        self, mock_opik_client, monkeypatch
    ) -> None:
        mock_opik_client()
        optimizer = ParameterOptimizer(verbose=0)
        scores = iter([0.1] + [0.2] * 100)

        def evaluate(**kwargs):
            time.sleep(0.01)
            return next(scores)

        monkeypatch.setattr(optimizer, "evaluate_prompt", evaluate)
        result = optimizer.optimize_parameter(
            prompt=ChatPrompt(name="p", system="A", model="agent-model"),
            dataset=make_mock_dataset(),
            metric=make_simple_metric(),
            parameter_space=space(float_param("temperature")),
            max_trials=50,
            timeout=0.001,
        )
        assert result.details["trials_completed"] < 50
        assert result.details["finish_reason"] == "timeout"
        assert result.details["stopped_early"] is True

    def test_deliberate_no_prompt_roles_does_not_warn(
        self, mock_opik_client, monkeypatch
    ) -> None:
        from opik_optimizer import base_optimizer

        mock_opik_client()
        optimizer = ParameterOptimizer(verbose=0)
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 1.0)
        warning = MagicMock()
        monkeypatch.setattr(base_optimizer.logger, "warning", warning)
        optimizer.optimize_parameter(
            prompt=ChatPrompt(name="p", system="A", model="agent-model"),
            dataset=make_mock_dataset(),
            metric=make_simple_metric(),
            parameter_space=space(float_param("temperature")),
        )
        messages = [str(call.args[0]) for call in warning.call_args_list]
        assert not [m for m in messages if "resolved to no roles" in m]
