import pydantic
import pytest

from opik.llm_usage.opik_usage import OpikUsage
from opik.llm_usage.typesafe_usage import TypeSafeUsage


def test_typesafe_usage_creation__happyflow():
    usage = TypeSafeUsage.from_original_usage_dict(
        {"input_tokens": 120, "output_tokens": 12}
    )
    assert usage.input_tokens == 120
    assert usage.output_tokens == 12


def test_typesafe_usage__to_backend_compatible_flat_dict__happyflow():
    usage = TypeSafeUsage.from_original_usage_dict(
        {"input_tokens": 120, "output_tokens": 12}
    )
    assert usage.to_backend_compatible_flat_dict("original_usage") == {
        "original_usage.input_tokens": 120,
        "original_usage.output_tokens": 12,
    }


def test_typesafe_usage__unreported_tokens__dropped_from_flat_dict():
    usage = TypeSafeUsage.from_original_usage_dict(
        {"input_tokens": 120, "output_tokens": None}
    )
    assert usage.to_backend_compatible_flat_dict("original_usage") == {
        "original_usage.input_tokens": 120,
    }


def test_typesafe_usage__unknown_extra_field__kept_when_int():
    usage = TypeSafeUsage.from_original_usage_dict(
        {"input_tokens": 120, "output_tokens": 12, "cached_tokens": 7, "ratio": 0.5}
    )
    assert usage.to_backend_compatible_flat_dict("original_usage") == {
        "original_usage.input_tokens": 120,
        "original_usage.output_tokens": 12,
        "original_usage.cached_tokens": 7,
    }


def test_typesafe_usage__invalid_data_passed__validation_error_is_raised():
    with pytest.raises(pydantic.ValidationError):
        TypeSafeUsage.from_original_usage_dict(
            {"input_tokens": "invalid", "output_tokens": 12}
        )


def test_opik_usage_from_typesafe_dict__happyflow():
    opik_usage = OpikUsage.from_typesafe_dict(
        {"input_tokens": 120, "output_tokens": 12}
    )
    assert opik_usage.prompt_tokens == 120
    assert opik_usage.completion_tokens == 12
    assert opik_usage.total_tokens == 132
    assert isinstance(opik_usage.provider_usage, TypeSafeUsage)
    assert opik_usage.to_backend_compatible_full_usage_dict() == {
        "prompt_tokens": 120,
        "completion_tokens": 12,
        "total_tokens": 132,
        "original_usage.input_tokens": 120,
        "original_usage.output_tokens": 12,
    }


def test_opik_usage_from_typesafe_dict__only_input_reported__total_equals_input():
    opik_usage = OpikUsage.from_typesafe_dict({"input_tokens": 120})
    assert opik_usage.prompt_tokens == 120
    assert opik_usage.completion_tokens is None
    assert opik_usage.total_tokens == 120


def test_opik_usage_from_typesafe_dict__nothing_reported__totals_are_none():
    opik_usage = OpikUsage.from_typesafe_dict({})
    assert opik_usage.prompt_tokens is None
    assert opik_usage.completion_tokens is None
    assert opik_usage.total_tokens is None
    assert opik_usage.to_backend_compatible_full_usage_dict() == {}
