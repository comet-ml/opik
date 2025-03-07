import pytest
import pydantic
from opik.llm_usage.google_usage import GoogleGeminiUsage


def test_google_gemini_usage_creation__happyflow():
    usage_data = {
        "candidates_token_count": 100,
        "prompt_token_count": 50,
        "total_token_count": 150,
        "cached_content_token_count": 20,
    }
    usage = GoogleGeminiUsage.from_original_usage_dict(usage_data)
    assert usage.candidates_token_count == 100
    assert usage.prompt_token_count == 50
    assert usage.total_token_count == 150
    assert usage.cached_content_token_count == 20


def test_google_gemini_usage_creation__no_cache_key__cached_content_token_count_is_None():
    usage_data = {
        "candidates_token_count": 100,
        "prompt_token_count": 50,
        "total_token_count": 150,
    }
    usage = GoogleGeminiUsage.from_original_usage_dict(usage_data)
    assert usage.candidates_token_count == 100
    assert usage.prompt_token_count == 50
    assert usage.total_token_count == 150
    assert usage.cached_content_token_count is None


def test_google_gemini_usage__to_backend_compatible_flat_dict__happyflow():
    usage_data = {
        "candidates_token_count": 100,
        "prompt_token_count": 50,
        "total_token_count": 150,
        "cached_content_token_count": 10,
    }
    usage = GoogleGeminiUsage.from_original_usage_dict(usage_data)
    flat_dict = usage.to_backend_compatible_flat_dict("original_usage")
    assert flat_dict == {
        "original_usage.candidates_token_count": 100,
        "original_usage.prompt_token_count": 50,
        "original_usage.total_token_count": 150,
        "original_usage.cached_content_token_count": 10,
    }


def test_google_gemini_usage__to_backend_compatible_flat_dict__no_cache_tokens_key():
    usage_data = {
        "candidates_token_count": 100,
        "prompt_token_count": 50,
        "total_token_count": 150,
    }
    usage = GoogleGeminiUsage.from_original_usage_dict(usage_data)
    flat_dict = usage.to_backend_compatible_flat_dict("original_usage")
    assert flat_dict == {
        "original_usage.candidates_token_count": 100,
        "original_usage.prompt_token_count": 50,
        "original_usage.total_token_count": 150,
    }


def test_google_gemini_usage__invalid_data_passed__validation_error_is_raised():
    usage_data = {
        "candidates_token_count": "invalid",
        "prompt_token_count": None,
        "total_token_count": 150,
        "cached_content_token_count": "wrong_type",
    }
    with pytest.raises(pydantic.ValidationError):
        GoogleGeminiUsage.from_original_usage_dict(usage_data)


def test_google_gemini_usage__extra_unknown_keys_are_passed__fields_are_accepted__all_integers_included_to_the_resulting_flat_dict():
    usage_data = {
        "candidates_token_count": 100,
        "prompt_token_count": 50,
        "total_token_count": 150,
        "cached_content_token_count": 10,
        "some_newly_added_int": 42,
        "some_newly_added_details_dict": {
            "detail_int": 333,
            "detail_string": "some-string",
        },
    }

    usage = GoogleGeminiUsage.from_original_usage_dict(usage_data)
    assert usage.some_newly_added_int == 42
    assert usage.some_newly_added_details_dict == {
        "detail_int": 333,
        "detail_string": "some-string",
    }

    flat_dict = usage.to_backend_compatible_flat_dict("original_usage")
    assert flat_dict == {
        "original_usage.candidates_token_count": 100,
        "original_usage.prompt_token_count": 50,
        "original_usage.total_token_count": 150,
        "original_usage.cached_content_token_count": 10,
        "original_usage.some_newly_added_int": 42,
        "original_usage.some_newly_added_details_dict.detail_int": 333,
    }
