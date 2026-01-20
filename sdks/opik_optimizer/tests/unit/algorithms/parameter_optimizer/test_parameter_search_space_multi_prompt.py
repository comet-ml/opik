# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.algorithms.parameter_optimizer.ops.search_ops import (
    ParameterSearchSpace,
    ParameterSpec,
)
from opik_optimizer.algorithms.parameter_optimizer.types import ParameterType
from tests.unit.algorithms.parameter_optimizer._test_builders import (
    categorical_param,
    float_param,
    space,
)


class TestExpandForPrompts:
    """Tests for ParameterSearchSpace.expand_for_prompts()."""

    def test_expands_unprefixed_params(self) -> None:
        expanded = space(float_param("temperature")).expand_for_prompts(["analyze", "respond"])
        names = [p.name for p in expanded.parameters]
        assert "analyze.temperature" in names
        assert "respond.temperature" in names

    def test_preserves_already_prefixed_params(self) -> None:
        expanded = space(float_param("analyze.temperature")).expand_for_prompts(
            ["analyze", "respond"]
        )
        assert len(expanded.parameters) == 1
        assert expanded.parameters[0].name == "analyze.temperature"

    def test_handles_mixed_prefixed_and_unprefixed(self) -> None:
        expanded = space(
            float_param("temperature"),
            float_param("analyze.top_p", low=0.1, high=1.0),
        ).expand_for_prompts(["analyze", "respond"])

        names = [p.name for p in expanded.parameters]
        assert "analyze.temperature" in names
        assert "respond.temperature" in names
        assert "analyze.top_p" in names
        assert "respond.top_p" not in names

    def test_expands_multiple_params(self) -> None:
        expanded = space(
            float_param("temperature"),
            float_param("top_p", low=0.1, high=1.0),
        ).expand_for_prompts(["a", "b"])

        names = [p.name for p in expanded.parameters]
        assert "a.temperature" in names
        assert "b.temperature" in names
        assert "a.top_p" in names
        assert "b.top_p" in names

    def test_single_prompt_expansion(self) -> None:
        expanded = space(float_param("temperature")).expand_for_prompts(["my_prompt"])
        assert len(expanded.parameters) == 1
        assert expanded.parameters[0].name == "my_prompt.temperature"

    def test_preserves_spec_properties(self) -> None:
        expanded = space(
            float_param("temperature", low=0.1, high=2.0, step=0.1, scale="log")
        ).expand_for_prompts(["test"])

        spec = expanded.parameters[0]
        assert spec.name == "test.temperature"
        assert spec.distribution == ParameterType.FLOAT
        assert spec.low == 0.1
        assert spec.high == 2.0
        assert spec.step == 0.1
        assert spec.scale == "log"

    def test_categorical_param_expansion(self) -> None:
        expanded = space(
            categorical_param("model", choices=["gpt-4o", "gpt-4o-mini"])
        ).expand_for_prompts(["p1", "p2"])

        assert len(expanded.parameters) == 2
        for spec in expanded.parameters:
            assert spec.distribution == ParameterType.CATEGORICAL
            assert spec.choices == ["gpt-4o", "gpt-4o-mini"]


class TestApplyToPrompts:
    """Tests for ParameterSearchSpace.apply_to_prompts()."""

    def test_applies_prefixed_values_to_correct_prompts(self) -> None:
        s = space(
            float_param("analyze.temperature"),
            float_param("respond.temperature"),
        )

        prompts = {
            "analyze": ChatPrompt(name="analyze", system="Analyze"),
            "respond": ChatPrompt(name="respond", system="Respond"),
        }

        result = s.apply_to_prompts(
            prompts,
            {"analyze.temperature": 0.3, "respond.temperature": 0.8},
            base_model_kwargs={},
        )

        assert result["analyze"].model_kwargs["temperature"] == 0.3
        assert result["respond"].model_kwargs["temperature"] == 0.8

    def test_does_not_mutate_original_prompts(self) -> None:
        s = space(float_param("p.temperature"))

        original = ChatPrompt(name="p", system="Test")
        original.model_kwargs = {"temperature": 0.5}
        prompts = {"p": original}

        result = s.apply_to_prompts(
            prompts,
            {"p.temperature": 0.9},
            base_model_kwargs=None,
        )

        assert result["p"] is not original
        assert original.model_kwargs["temperature"] == 0.5
        assert result["p"].model_kwargs["temperature"] == 0.9

    def test_handles_missing_prompt_prefix(self) -> None:
        s = ParameterSearchSpace(
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

        result = s.apply_to_prompts(
            prompts,
            {"other.temperature": 0.5},
            base_model_kwargs={},
        )

        assert "my_prompt" in result
        assert "temperature" not in (result["my_prompt"].model_kwargs or {})

    def test_applies_model_parameter_to_each_prompt(self) -> None:
        s = ParameterSearchSpace(
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

        result = s.apply_to_prompts(
            prompts,
            {"analyze.model": "gpt-4o", "respond.model": "gpt-4o-mini"},
            base_model_kwargs={},
        )

        assert result["analyze"].model == "gpt-4o"
        assert result["respond"].model == "gpt-4o-mini"

    def test_expand_and_apply_model_parameter(self) -> None:
        s = ParameterSearchSpace(
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

        expanded = s.expand_for_prompts(["p1", "p2"])
        names = [p.name for p in expanded.parameters]
        assert "p1.model" in names
        assert "p2.model" in names

        result = expanded.apply_to_prompts(
            prompts,
            {"p1.model": "gpt-4o", "p2.model": "gpt-4o-mini"},
            base_model_kwargs={},
        )

        assert result["p1"].model == "gpt-4o"
        assert result["p2"].model == "gpt-4o-mini"

    def test_mixed_model_and_temperature_expansion(self) -> None:
        s = ParameterSearchSpace(
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

        expanded = s.expand_for_prompts(["a", "b"])

        prompts = {
            "a": ChatPrompt(name="a", system="A", model="default"),
            "b": ChatPrompt(name="b", system="B", model="default"),
        }

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


class TestBackwardCompatibility:
    def test_single_prompt_parameter_expansion(self) -> None:
        s = ParameterSearchSpace(
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
        expanded = s.expand_for_prompts([prompt.name])

        assert len(expanded.parameters) == 1
        assert expanded.parameters[0].name == "my_single_prompt.temperature"

    def test_describe_with_expanded_params(self) -> None:
        s = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="temperature",
                    distribution=ParameterType.FLOAT,
                    low=0.0,
                    high=1.0,
                ),
            ]
        )

        expanded = s.expand_for_prompts(["p1", "p2"])
        description = expanded.describe()

        assert "p1.temperature" in description
        assert "p2.temperature" in description
        assert description["p1.temperature"]["min"] == 0.0
        assert description["p1.temperature"]["max"] == 1.0

