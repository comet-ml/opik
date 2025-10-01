import pytest

from opik_optimizer.optimization_config.chat_prompt import ChatPrompt
from opik_optimizer.parameter_optimizer.search_space import (
    ParameterSearchSpace,
    ParameterSpec,
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
    prompt = ChatPrompt(system="Hello")
    prompt.model = "gpt-4o-mini"
    prompt.model_kwargs = {"temperature": 0.1}

    space = ParameterSearchSpace(
        parameters=[
            ParameterSpec(name="temperature", type=ParameterType.FLOAT, low=0.0, high=1.0),
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
            ParameterSpec(name="temperature", type=ParameterType.FLOAT, low=0.0, high=1.0),
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
