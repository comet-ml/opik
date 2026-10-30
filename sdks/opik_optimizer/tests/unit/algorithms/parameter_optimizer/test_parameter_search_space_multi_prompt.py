# mypy: disable-error-code=no-untyped-def

from __future__ import annotations


from opik_optimizer import ChatPrompt
from opik_optimizer.algorithms.parameter_optimizer.ops.search_ops import (
    ParameterSearchSpace,
    ParameterSpec,
)
from opik_optimizer.algorithms.parameter_optimizer.types import ParameterType
from tests.unit.algorithms.parameter_optimizer._test_builders import (
    float_param,
    space,
)


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
