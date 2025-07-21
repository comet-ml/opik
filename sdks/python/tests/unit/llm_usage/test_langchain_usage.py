import pydantic
import pytest

from opik.llm_usage import langchain_usage


def test_langchain_usage__happy_flow():
    usage_dict = {
        "input_tokens": 2,
        "output_tokens": 9,
        "total_tokens": 1121,
        "input_token_details": {"cache_read": 0},
        "output_token_details": {"reasoning": 1110},
    }

    usage = langchain_usage.LangChainUsage.from_original_usage_dict(usage_dict)
    assert usage.input_tokens == 2
    assert usage.output_tokens == 9
    assert usage.total_tokens == 1121
    assert usage.input_token_details.cache_read == 0
    assert usage.output_token_details.reasoning == 1110


def test_langchain_usage__no_input_token_details__no_output_token_details():
    usage_dict = {
        "input_tokens": 2,
        "output_tokens": 9,
        "total_tokens": 1121,
    }

    usage = langchain_usage.LangChainUsage.from_original_usage_dict(usage_dict)
    assert usage.input_tokens == 2
    assert usage.output_tokens == 9
    assert usage.total_tokens == 1121
    assert usage.input_token_details is None
    assert usage.output_token_details is None


def test_langchain_usage__invalid_data_passed__validation_error_is_raised():
    usage_dict = {
        "input_tokens": "invalid",
        "output_tokens": None,
        "total_tokens": 1121,
        "input_token_details": {"cache_read": "wrong_type"},
        "output_token_details": {"reasoning": 1110},
    }

    with pytest.raises(pydantic.ValidationError):
        langchain_usage.LangChainUsage.from_original_usage_dict(usage_dict)


def test_langchain_usage__to_backend_compatible_flat_dict__happy_flow():
    usage_dict = {
        "input_tokens": 2,
        "output_tokens": 9,
        "total_tokens": 1121,
        "input_token_details": {"cache_read": 0},
        "output_token_details": {"reasoning": 1110},
    }

    usage = langchain_usage.LangChainUsage.from_original_usage_dict(usage_dict)
    flat_dict = usage.to_backend_compatible_flat_dict("original_usage")
    assert flat_dict == {
        "original_usage.input_tokens": 2,
        "original_usage.output_tokens": 9,
        "original_usage.total_tokens": 1121,
        "original_usage.input_token_details.cache_read": 0,
        "original_usage.output_token_details.reasoning": 1110,
    }
