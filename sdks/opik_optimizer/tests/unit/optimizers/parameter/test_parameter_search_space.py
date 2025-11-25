import pytest

import opik_optimizer
from opik_optimizer.algorithms.parameter_optimizer.parameter_search_space import (
    ParameterSearchSpace,
)
from opik_optimizer.algorithms.parameter_optimizer.parameter_spec import ParameterSpec
from opik_optimizer.algorithms.parameter_optimizer.search_space_types import (
    ParameterType,
)


def test_parameter_space_from_dict() -> None:
    space = ParameterSearchSpace.model_validate(
        {
            "temperature": {"type": "float", "min": 0.0, "max": 1.0},
            "top_k": {
                "type": "int",
                "min": 1,
                "max": 50,
                "target": "anthropic.top_k",
            },
            "model_choice": {
                "type": "categorical",
                "values": ["gpt-4o-mini", "gpt-4o"],
                "target": "model",
            },
        }
    )

    assert len(space.parameters) == 3
    temp_spec = next(spec for spec in space.parameters if spec.name == "temperature")
    assert temp_spec.distribution is ParameterType.FLOAT
    assert temp_spec.target_path.root == "model_kwargs"
    assert temp_spec.target_path.path == ("temperature",)

    top_k_spec = next(spec for spec in space.parameters if spec.name == "top_k")
    assert top_k_spec.target_path.root == "model_kwargs"
    assert top_k_spec.target_path.path == ("anthropic", "top_k")

    model_spec = next(spec for spec in space.parameters if spec.name == "model_choice")
    assert model_spec.target_path.root == "model"
    assert model_spec.target_path.path == ()


def test_apply_updates_prompt_without_mutating_original() -> None:
    prompt = opik_optimizer.ChatPrompt(system="Hello")
    prompt.model = "gpt-4o-mini"
    prompt.model_kwargs = {"temperature": 0.1}

    space = ParameterSearchSpace(
        parameters=[
            ParameterSpec(
                name="temperature", type=ParameterType.FLOAT, low=0.0, high=1.0
            ),
            ParameterSpec(
                name="top_k",
                type=ParameterType.INT,
                low=1,
                high=20,
                target=("anthropic", "top_k"),
            ),
            ParameterSpec(
                name="model_choice",
                type=ParameterType.CATEGORICAL,
                choices=["gpt-4o", "gpt-4o-mini"],
                target="model",
            ),
        ]
    )

    applied = space.apply(
        prompt,
        {"temperature": 0.5, "top_k": 5, "model_choice": "gpt-4o"},
        base_model_kwargs=prompt.model_kwargs,
    )

    assert applied is not prompt
    assert prompt.model == "gpt-4o-mini"
    assert prompt.model_kwargs == {"temperature": 0.1}
    assert applied.model == "gpt-4o"
    assert applied.model_kwargs["temperature"] == 0.5
    assert applied.model_kwargs["anthropic"]["top_k"] == 5


def test_values_to_model_kwargs_merges_base() -> None:
    space = ParameterSearchSpace(
        parameters=[
            ParameterSpec(
                name="temperature", type=ParameterType.FLOAT, low=0.0, high=1.0
            ),
            ParameterSpec(
                name="presence",
                type=ParameterType.FLOAT,
                low=-2.0,
                high=2.0,
                target="openai.extra_body.presence_penalty",
            ),
        ]
    )

    merged = space.values_to_model_kwargs(
        {"temperature": 0.2, "presence": 1.0}, base={"top_p": 0.9}
    )

    assert merged["temperature"] == 0.2
    assert merged["top_p"] == 0.9
    assert merged["openai"]["extra_body"]["presence_penalty"] == 1.0


def test_bool_parameter_defaults_choices() -> None:
    spec = ParameterSpec(name="use_cache", type=ParameterType.BOOL)
    assert spec.choices == [False, True]

    with pytest.raises(ValueError):
        ParameterSpec(name="invalid", type=ParameterType.CATEGORICAL)


def test_narrow_around_creates_smaller_range() -> None:
    space = ParameterSearchSpace.model_validate(
        {
            "temperature": {"type": "float", "min": 0.0, "max": 1.0},
            "top_p": {"type": "float", "min": 0.1, "max": 1.0},
        }
    )

    narrowed = space.narrow_around({"temperature": 0.6, "top_p": 0.9}, scale=0.2)

    temp_spec = next(spec for spec in narrowed.parameters if spec.name == "temperature")
    assert temp_spec.low is not None and temp_spec.high is not None
    assert temp_spec.low > 0.0
    assert temp_spec.high < 1.0

    top_p_spec = next(spec for spec in narrowed.parameters if spec.name == "top_p")
    assert top_p_spec.low is not None and top_p_spec.high is not None
    assert top_p_spec.low > 0.1
    assert top_p_spec.high <= 1.0


def test_describe_returns_summary() -> None:
    space = ParameterSearchSpace.model_validate(
        {
            "temperature": {"type": "float", "min": 0.0, "max": 1.0, "scale": "linear"},
            "mode": {"type": "categorical", "values": ["fast", "accurate"]},
        }
    )

    summary = space.describe()
    assert summary["temperature"]["min"] == 0.0
    assert summary["temperature"]["max"] == 1.0
    assert summary["temperature"]["type"] == "float"
    assert summary["mode"]["choices"] == ["fast", "accurate"]


def test_docs_basic_example() -> None:
    """Test the basic example from documentation."""
    parameter_space = ParameterSearchSpace(
        parameters=[
            {
                "name": "temperature",
                "distribution": "float",
                "low": 0.0,
                "high": 2.0,
            },
            {
                "name": "top_p",
                "distribution": "float",
                "low": 0.1,
                "high": 1.0,
            },
        ]
    )
    assert len(parameter_space.parameters) == 2
    temp = parameter_space.parameters[0]
    assert temp.name == "temperature"
    assert temp.distribution == ParameterType.FLOAT
    assert temp.low == 0.0
    assert temp.high == 2.0

    top_p = parameter_space.parameters[1]
    assert top_p.name == "top_p"
    assert top_p.distribution == ParameterType.FLOAT
    assert top_p.low == 0.1
    assert top_p.high == 1.0


def test_docs_advanced_example() -> None:
    """Test the advanced example from documentation."""
    parameter_space = ParameterSearchSpace(
        parameters=[
            {
                "name": "temperature",
                "distribution": "float",
                "low": 0.0,
                "high": 1.5,
                "step": 0.05,
            },
            {
                "name": "top_p",
                "distribution": "float",
                "low": 0.5,
                "high": 1.0,
            },
            {
                "name": "frequency_penalty",
                "distribution": "float",
                "low": -1.0,
                "high": 1.0,
            },
            {
                "name": "presence_penalty",
                "distribution": "float",
                "low": -1.0,
                "high": 1.0,
            },
            {
                "name": "model",
                "distribution": "categorical",
                "choices": [
                    "openai/gpt-4o-mini",
                    "openai/gpt-4o",
                    "openai/gpt-4-turbo",
                ],
            },
        ]
    )
    assert len(parameter_space.parameters) == 5
    # Verify temperature with step
    temp = next(p for p in parameter_space.parameters if p.name == "temperature")
    assert temp.step == 0.05
    # Verify categorical model
    model = next(p for p in parameter_space.parameters if p.name == "model")
    assert model.distribution == ParameterType.CATEGORICAL
    assert model.choices == [
        "openai/gpt-4o-mini",
        "openai/gpt-4o",
        "openai/gpt-4-turbo",
    ]


def test_docs_float_parameter_example() -> None:
    """Test float parameter example from documentation."""
    spec = ParameterSpec(
        name="temperature",
        distribution="float",
        low=0.0,
        high=2.0,
        step=0.1,
        scale="linear",
    )
    assert spec.distribution == ParameterType.FLOAT
    assert spec.low == 0.0
    assert spec.high == 2.0
    assert spec.step == 0.1
    assert spec.scale == "linear"


def test_docs_int_parameter_example() -> None:
    """Test integer parameter example from documentation."""
    spec = ParameterSpec(
        name="max_tokens",
        distribution="int",
        low=100,
        high=4000,
        step=100,
        scale="linear",
    )
    assert spec.distribution == ParameterType.INT
    assert spec.low == 100
    assert spec.high == 4000
    assert spec.step == 100


def test_docs_categorical_parameter_example() -> None:
    """Test categorical parameter example from documentation."""
    spec = ParameterSpec(
        name="model",
        distribution="categorical",
        choices=["gpt-4o-mini", "gpt-4o", "claude-3-haiku"],
    )
    assert spec.distribution == ParameterType.CATEGORICAL
    assert spec.choices == ["gpt-4o-mini", "gpt-4o", "claude-3-haiku"]


def test_docs_bool_parameter_example() -> None:
    """Test boolean parameter example from documentation."""
    spec = ParameterSpec(name="stream", distribution="bool")
    assert spec.distribution == ParameterType.BOOL
    assert spec.choices == [False, True]


def test_docs_nested_parameter_example() -> None:
    """Test nested parameter targeting from documentation."""
    spec = ParameterSpec(
        name="model_kwargs.response_format.type",
        distribution="categorical",
        choices=["text", "json_object"],
    )
    assert spec.name == "model_kwargs.response_format.type"
    assert spec.distribution == ParameterType.CATEGORICAL
    assert spec.choices == ["text", "json_object"]
