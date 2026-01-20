# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import pytest

from opik_optimizer import ChatPrompt, ParameterOptimizer
from tests.unit.algorithms.parameter_optimizer._test_builders import float_param, space
from tests.unit.fixtures import assert_baseline_early_stop, make_baseline_prompt
from tests.unit.test_helpers import make_mock_dataset, make_simple_metric, STANDARD_DATASET_ITEMS


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
    def test_initialization(self, kwargs: dict[str, Any], expected: dict[str, Any]) -> None:
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


class TestNormalizePromptInput:
    def test_single_prompt_normalized_to_dict(self) -> None:
        optimizer = ParameterOptimizer(model="gpt-4o-mini", verbose=0)
        prompt = ChatPrompt(name="test_prompt", system="Hello")

        prompts, is_single = optimizer._normalize_prompt_input(prompt)

        assert isinstance(prompts, dict)
        assert "test_prompt" in prompts
        assert is_single is True

    def test_dict_prompt_kept_as_dict(self) -> None:
        optimizer = ParameterOptimizer(model="gpt-4o-mini", verbose=0)
        prompt_dict = {
            "a": ChatPrompt(name="a", system="A"),
            "b": ChatPrompt(name="b", system="B"),
        }

        prompts, is_single = optimizer._normalize_prompt_input(prompt_dict)

        assert prompts is prompt_dict
        assert is_single is False

