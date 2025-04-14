import pydantic
import pytest

from opik.llm_usage.openai_chat_completions_usage import OpenAICompletionsUsage


def test_openai_completions_usage_creation__happyflow():
    usage_data = {
        "completion_tokens": 100,
        "prompt_tokens": 200,
        "total_tokens": 300,
        "completion_tokens_details": {
            "accepted_prediction_tokens": 50,
            "audio_tokens": 20,
        },
        "prompt_tokens_details": {
            "audio_tokens": 10,
            "cached_tokens": 30,
        },
    }
    usage = OpenAICompletionsUsage.from_original_usage_dict(usage_data)
    assert usage.completion_tokens == 100
    assert usage.prompt_tokens == 200
    assert usage.total_tokens == 300
    assert usage.completion_tokens_details.accepted_prediction_tokens == 50
    assert usage.completion_tokens_details.audio_tokens == 20
    assert usage.prompt_tokens_details.audio_tokens == 10
    assert usage.prompt_tokens_details.cached_tokens == 30


def test_openai_completions_usage_creation__no_details_keys__details_are_None():
    usage_data = {
        "completion_tokens": 100,
        "prompt_tokens": 200,
        "total_tokens": 300,
    }
    usage = OpenAICompletionsUsage.from_original_usage_dict(usage_data)
    assert usage.completion_tokens == 100
    assert usage.prompt_tokens == 200
    assert usage.total_tokens == 300
    assert usage.completion_tokens_details is None
    assert usage.prompt_tokens_details is None


def test_openai_completions_usage__to_backend_compatible_flat_dict__happyflow():
    usage_data = {
        "completion_tokens": 100,
        "prompt_tokens": 200,
        "total_tokens": 300,
        "completion_tokens_details": {
            "accepted_prediction_tokens": 50,
            "audio_tokens": 20,
        },
        "prompt_tokens_details": {
            "audio_tokens": 10,
            "cached_tokens": 30,
        },
    }
    usage = OpenAICompletionsUsage.from_original_usage_dict(usage_data)
    flat_dict = usage.to_backend_compatible_flat_dict("original_usage")
    assert flat_dict == {
        "original_usage.completion_tokens": 100,
        "original_usage.prompt_tokens": 200,
        "original_usage.total_tokens": 300,
        "original_usage.completion_tokens_details.accepted_prediction_tokens": 50,
        "original_usage.completion_tokens_details.audio_tokens": 20,
        "original_usage.prompt_tokens_details.audio_tokens": 10,
        "original_usage.prompt_tokens_details.cached_tokens": 30,
    }


def test_openai_completions_usage__invalid_data_passed__validation_error_is_raised():
    usage_data = {
        "completion_tokens": "invalid",
        "prompt_tokens": None,
        "total_tokens": 300,
        "completion_tokens_details": "not_a_dict",
    }
    with pytest.raises(pydantic.ValidationError):
        OpenAICompletionsUsage.from_original_usage_dict(usage_data)


def test_openai_completions_usage__extra_unknown_keys_are_passed__fields_are_accepted__all_integers_included_to_the_resulting_flat_dict():
    usage_data = {
        "completion_tokens": 100,
        "prompt_tokens": 200,
        "total_tokens": 300,
        "extra_integer": 99,
        "completion_tokens_details": {
            "accepted_prediction_tokens": 40,
            "extra_completion_detail_int": 888,
            "ignored_string": "ignored",
        },
        "prompt_tokens_details": {
            "audio_tokens": 10,
            "cached_tokens": 30,
            "extra_prompt_detail_int": 111,
            "ignored_string": "ignored",
        },
        "extra_details_dict": {
            "extra_detail_int": 0,
            "ignored_string": "ignored",
        },
    }
    usage = OpenAICompletionsUsage.from_original_usage_dict(usage_data)
    assert usage.extra_integer == 99
    assert usage.extra_details_dict == {
        "extra_detail_int": 0,
        "ignored_string": "ignored",
    }
    assert usage.completion_tokens_details.extra_completion_detail_int == 888
    assert usage.prompt_tokens_details.extra_prompt_detail_int == 111

    flat_dict = usage.to_backend_compatible_flat_dict("original_usage")
    assert flat_dict == {
        "original_usage.completion_tokens": 100,
        "original_usage.prompt_tokens": 200,
        "original_usage.total_tokens": 300,
        "original_usage.extra_integer": 99,
        "original_usage.completion_tokens_details.accepted_prediction_tokens": 40,
        "original_usage.completion_tokens_details.extra_completion_detail_int": 888,
        "original_usage.prompt_tokens_details.audio_tokens": 10,
        "original_usage.prompt_tokens_details.cached_tokens": 30,
        "original_usage.prompt_tokens_details.extra_prompt_detail_int": 111,
        "original_usage.extra_details_dict.extra_detail_int": 0,
    }
