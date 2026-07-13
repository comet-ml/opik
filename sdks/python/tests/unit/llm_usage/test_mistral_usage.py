import pytest
import pydantic

from opik.llm_usage import build_opik_usage
from opik.llm_usage.mistral_usage import MistralUsage
from opik.types import LLMProvider


def test_mistral_usage_creation__happyflow():
    usage_data = {
        "prompt_tokens": 22,
        "completion_tokens": 3,
        "total_tokens": 25,
        "prompt_tokens_details": {"cached_tokens": 0},
    }
    usage = MistralUsage.from_original_usage_dict(usage_data)
    assert usage.prompt_tokens == 22
    assert usage.completion_tokens == 3
    assert usage.total_tokens == 25
    assert usage.prompt_tokens_details.cached_tokens == 0


def test_mistral_usage_creation__no_details__details_is_None():
    usage_data = {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
    usage = MistralUsage.from_original_usage_dict(usage_data)
    assert usage.prompt_tokens_details is None


def test_mistral_usage__to_backend_compatible_flat_dict__happyflow():
    usage_data = {
        "prompt_tokens": 22,
        "completion_tokens": 3,
        "total_tokens": 25,
        "prompt_tokens_details": {"cached_tokens": 0},
    }
    usage = MistralUsage.from_original_usage_dict(usage_data)
    flat_dict = usage.to_backend_compatible_flat_dict("original_usage")
    assert flat_dict == {
        "original_usage.prompt_tokens": 22,
        "original_usage.completion_tokens": 3,
        "original_usage.total_tokens": 25,
        "original_usage.prompt_tokens_details.cached_tokens": 0,
    }


def test_mistral_usage__non_int_extra_field_dropped_from_flat_dict():
    # prompt_audio_seconds is a float; only integer values survive in the
    # backend-compatible flat dict.
    usage_data = {
        "prompt_tokens": 10,
        "completion_tokens": 5,
        "total_tokens": 15,
        "prompt_audio_seconds": 1.5,
    }
    usage = MistralUsage.from_original_usage_dict(usage_data)
    flat_dict = usage.to_backend_compatible_flat_dict("original_usage")
    assert flat_dict == {
        "original_usage.prompt_tokens": 10,
        "original_usage.completion_tokens": 5,
        "original_usage.total_tokens": 15,
    }


def test_mistral_usage__invalid_data_passed__validation_error_is_raised():
    usage_data = {
        "prompt_tokens": "invalid",
        "completion_tokens": None,
        "total_tokens": 15,
    }
    with pytest.raises(pydantic.ValidationError):
        MistralUsage.from_original_usage_dict(usage_data)


def test_build_opik_usage__mistral_provider__produces_opik_usage():
    usage_data = {
        "prompt_tokens": 22,
        "completion_tokens": 3,
        "total_tokens": 25,
        "prompt_tokens_details": {"cached_tokens": 0},
    }
    opik_usage = build_opik_usage(provider=LLMProvider.MISTRALAI, usage=usage_data)
    assert opik_usage.prompt_tokens == 22
    assert opik_usage.completion_tokens == 3
    assert opik_usage.total_tokens == 25
    assert isinstance(opik_usage.provider_usage, MistralUsage)
