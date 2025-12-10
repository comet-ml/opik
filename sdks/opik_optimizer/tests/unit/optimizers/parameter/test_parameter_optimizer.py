"""
Unit tests for ParameterOptimizer multi-prompt and parameter expansion features.

Tests cover:
- expand_for_prompts: Auto-expansion of parameters per prompt
- apply_to_prompts: Application of prefixed parameters to prompts
- Multi-prompt handling in optimizer
- Single prompt backward compatibility
"""

from opik_optimizer import ChatPrompt
from opik_optimizer.algorithms.parameter_optimizer.parameter_search_space import (
    ParameterSearchSpace,
)
from opik_optimizer.algorithms.parameter_optimizer.parameter_spec import ParameterSpec
from opik_optimizer.algorithms.parameter_optimizer.search_space_types import (
    ParameterType,
)


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
                    low=0.0,
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
        assert spec.low == 0.0
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
