# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

import time
from typing import Any

import litellm
import pytest

from opik_optimizer import ChatPrompt, ParameterOptimizer
from tests.unit.algorithms.parameter_optimizer._test_builders import (
    categorical_param,
    float_param,
    space,
)
from tests.unit.test_helpers import (
    make_mock_dataset,
    make_simple_metric,
)


class TestParameterOptimizerInit:
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
