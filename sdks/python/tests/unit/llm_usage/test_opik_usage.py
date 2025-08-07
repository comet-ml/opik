import pytest
import pydantic
from opik.llm_usage.opik_usage import OpikUsage


def test_opik_usage__from_openai_completions_dict__happyflow():
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
        "video_seconds": 10,
    }
    usage = OpikUsage.from_openai_completions_dict(usage_data)
    assert usage.completion_tokens == 100
    assert usage.prompt_tokens == 200
    assert usage.total_tokens == 300
    assert usage.provider_usage.completion_tokens == 100
    assert usage.provider_usage.prompt_tokens == 200
    assert usage.provider_usage.total_tokens == 300
    assert usage.provider_usage.video_seconds == 10


def test_opik_usage__from_google_dict__happyflow():
    usage_data = {
        "candidates_token_count": 100,
        "prompt_token_count": 200,
        "total_token_count": 300,
        "cached_content_token_count": 50,
    }
    usage = OpikUsage.from_google_dict(usage_data)
    assert usage.completion_tokens == 100
    assert usage.prompt_tokens == 200
    assert usage.total_tokens == 300
    assert usage.provider_usage.candidates_token_count == 100
    assert usage.provider_usage.prompt_token_count == 200
    assert usage.provider_usage.total_token_count == 300


def test_opik_usage__to_backend_compatible_full_usage_dict__openai_source():
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
    usage = OpikUsage.from_openai_completions_dict(usage_data)
    full_dict = usage.to_backend_compatible_full_usage_dict()
    assert full_dict == {
        "completion_tokens": 100,
        "prompt_tokens": 200,
        "total_tokens": 300,
        "original_usage.completion_tokens": 100,
        "original_usage.prompt_tokens": 200,
        "original_usage.total_tokens": 300,
        "original_usage.completion_tokens_details.accepted_prediction_tokens": 50,
        "original_usage.completion_tokens_details.audio_tokens": 20,
        "original_usage.prompt_tokens_details.audio_tokens": 10,
        "original_usage.prompt_tokens_details.cached_tokens": 30,
    }


def test_opik_usage__to_backend_compatible_full_usage_dict__google_source():
    usage_data = {
        "candidates_token_count": 100,
        "prompt_token_count": 200,
        "total_token_count": 300,
        "cached_content_token_count": 50,
    }
    usage = OpikUsage.from_google_dict(usage_data)
    full_dict = usage.to_backend_compatible_full_usage_dict()
    assert full_dict == {
        "completion_tokens": 100,
        "prompt_tokens": 200,
        "total_tokens": 300,
        "original_usage.candidates_token_count": 100,
        "original_usage.prompt_token_count": 200,
        "original_usage.total_token_count": 300,
        "original_usage.cached_content_token_count": 50,
    }


def test_opik_usage__to_backend_compatible_full_usage_dict__anthropic_source():
    usage_data = {
        "input_tokens": 200,
        "output_tokens": 100,
        "cache_creation_input_tokens": 50,
        "cache_read_input_tokens": 30,
    }
    usage = OpikUsage.from_anthropic_dict(usage_data)
    full_dict = usage.to_backend_compatible_full_usage_dict()
    assert full_dict == {
        "completion_tokens": 150,
        "prompt_tokens": 230,
        "total_tokens": 380,
        "original_usage.input_tokens": 200,
        "original_usage.output_tokens": 100,
        "original_usage.cache_creation_input_tokens": 50,
        "original_usage.cache_read_input_tokens": 30,
    }


def test_opik_usage__invalid_data_passed__validation_error_is_raised():
    usage_data = {"a": 123}
    with pytest.raises(pydantic.ValidationError):
        OpikUsage.from_openai_completions_dict(usage_data)
    with pytest.raises(pydantic.ValidationError):
        OpikUsage.from_google_dict(usage_data)
    with pytest.raises(pydantic.ValidationError):
        OpikUsage.from_anthropic_dict(usage_data)
