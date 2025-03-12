import pytest
import pydantic
from opik.llm_usage.anthropic_usage import AnthropicUsage


def test_anthropic_usage_creation__happyflow():
    usage_data = {
        "input_tokens": 200,
        "output_tokens": 100,
        "cache_creation_input_tokens": 50,
        "cache_read_input_tokens": 30,
    }
    usage = AnthropicUsage.from_original_usage_dict(usage_data)
    assert usage.input_tokens == 200
    assert usage.output_tokens == 100
    assert usage.cache_creation_input_tokens == 50
    assert usage.cache_read_input_tokens == 30


def test_anthropic_usage_creation__no_cache_keys__cache_values_are_None():
    usage_data = {
        "input_tokens": 200,
        "output_tokens": 100,
    }
    usage = AnthropicUsage.from_original_usage_dict(usage_data)
    assert usage.input_tokens == 200
    assert usage.output_tokens == 100
    assert usage.cache_creation_input_tokens is None
    assert usage.cache_read_input_tokens is None


def test_anthropic_usage__to_backend_compatible_flat_dict__happyflow():
    usage_data = {
        "input_tokens": 200,
        "output_tokens": 100,
        "cache_creation_input_tokens": 50,
        "cache_read_input_tokens": 30,
    }
    usage = AnthropicUsage.from_original_usage_dict(usage_data)
    flat_dict = usage.to_backend_compatible_flat_dict("original_usage")
    assert flat_dict == {
        "original_usage.input_tokens": 200,
        "original_usage.output_tokens": 100,
        "original_usage.cache_creation_input_tokens": 50,
        "original_usage.cache_read_input_tokens": 30,
    }


def test_anthropic_usage__to_backend_compatible_flat_dict__no_cache_keys():
    usage_data = {
        "input_tokens": 200,
        "output_tokens": 100,
    }
    usage = AnthropicUsage.from_original_usage_dict(usage_data)
    flat_dict = usage.to_backend_compatible_flat_dict("original_usage")
    assert flat_dict == {
        "original_usage.input_tokens": 200,
        "original_usage.output_tokens": 100,
    }


def test_anthropic_usage__invalid_data_passed__validation_error_is_raised():
    usage_data = {
        "input_tokens": "invalid",
        "output_tokens": None,
        "cache_creation_input_tokens": "wrong_type",
    }
    with pytest.raises(pydantic.ValidationError):
        AnthropicUsage.from_original_usage_dict(usage_data)


def test_anthropic_usage__extra_unknown_keys_are_passed__fields_are_accepted__all_integers_included_to_the_resulting_flat_dict():
    usage_data = {
        "input_tokens": 200,
        "output_tokens": 100,
        "cache_creation_input_tokens": 50,
        "cache_read_input_tokens": 30,
        "some_newly_added_int": 42,
        "some_newly_added_details_dict": {
            "detail_int": 333,
            "detail_string": "some-string",
        },
    }

    usage = AnthropicUsage.from_original_usage_dict(usage_data)
    assert usage.some_newly_added_int == 42
    assert usage.some_newly_added_details_dict == {
        "detail_int": 333,
        "detail_string": "some-string",
    }

    flat_dict = usage.to_backend_compatible_flat_dict("original_usage")
    assert flat_dict == {
        "original_usage.input_tokens": 200,
        "original_usage.output_tokens": 100,
        "original_usage.cache_creation_input_tokens": 50,
        "original_usage.cache_read_input_tokens": 30,
        "original_usage.some_newly_added_int": 42,
        "original_usage.some_newly_added_details_dict.detail_int": 333,
    }
