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
        "completion_tokens": 100,
        "prompt_tokens": 280,  # 200 + 30 cache_read + 50 cache_creation
        "total_tokens": 380,
        "original_usage.input_tokens": 200,
        "original_usage.output_tokens": 100,
        "original_usage.cache_creation_input_tokens": 50,
        "original_usage.cache_read_input_tokens": 30,
    }


def test_opik_usage__to_backend_compatible_full_usage_dict__bedrock_source():
    usage_data = {
        "inputTokens": 200,
        "outputTokens": 100,
        "cacheWriteInputTokens": 50,
        "cacheReadInputTokens": 30,
    }
    usage = OpikUsage.from_bedrock_dict(usage_data)
    full_dict = usage.to_backend_compatible_full_usage_dict()
    assert full_dict == {
        "completion_tokens": 100,
        "prompt_tokens": 280,  # 200 + 30 cacheRead + 50 cacheWrite
        "total_tokens": 380,
        "original_usage.inputTokens": 200,
        "original_usage.outputTokens": 100,
        "original_usage.cacheWriteInputTokens": 50,
        "original_usage.cacheReadInputTokens": 30,
    }


def test_opik_usage__from_bedrock_dict__no_cache_tokens__counts_input_tokens_only():
    # Non-anthropic Bedrock subproviders (llama, mistral, nova) never report cache
    # counters, and a cache hit reports them as an explicit 0, so both must keep
    # the plain input count.
    usage = OpikUsage.from_bedrock_dict(
        {"inputTokens": 200, "outputTokens": 100, "totalTokens": 300}
    )
    assert usage.prompt_tokens == 200
    assert usage.completion_tokens == 100
    assert usage.total_tokens == 300

    usage_with_zero_cache = OpikUsage.from_bedrock_dict(
        {
            "inputTokens": 200,
            "outputTokens": 100,
            "totalTokens": 300,
            "cacheReadInputTokens": 0,
            "cacheWriteInputTokens": 0,
        }
    )
    assert usage_with_zero_cache.prompt_tokens == 200
    assert usage_with_zero_cache.total_tokens == 300


def test_opik_usage__equivalent_bedrock_and_anthropic_usage__agree_on_token_counts():
    # Bedrock runs the same Claude models as the Anthropic API and reports the
    # same two cache counters under different names, so two usage records that
    # describe the same call must normalise to the same numbers. These are two
    # separately written dicts, not a captured pair of responses.
    anthropic_usage = OpikUsage.from_anthropic_dict(
        {
            "input_tokens": 12,
            "output_tokens": 7,
            "cache_creation_input_tokens": 1024,
            "cache_read_input_tokens": 4096,
        }
    )
    bedrock_usage = OpikUsage.from_bedrock_dict(
        {
            "inputTokens": 12,
            "outputTokens": 7,
            "cacheWriteInputTokens": 1024,
            "cacheReadInputTokens": 4096,
        }
    )

    assert bedrock_usage.prompt_tokens == anthropic_usage.prompt_tokens == 5132
    assert bedrock_usage.completion_tokens == anthropic_usage.completion_tokens
    assert bedrock_usage.total_tokens == anthropic_usage.total_tokens == 5139


def test_opik_usage__from_unknown_usage_dict__both_tokens_present__total_is_calculated():
    usage_data = {
        "prompt_tokens": 200,
        "completion_tokens": 100,
    }
    usage = OpikUsage.from_unknown_usage_dict(usage_data)
    assert usage.prompt_tokens == 200
    assert usage.completion_tokens == 100
    assert usage.total_tokens == 300


def test_opik_usage__from_unknown_usage_dict__only_prompt_tokens__total_is_none():
    usage_data = {
        "prompt_tokens": 200,
    }
    usage = OpikUsage.from_unknown_usage_dict(usage_data)
    assert usage.prompt_tokens == 200
    assert usage.completion_tokens is None
    assert usage.total_tokens is None


def test_opik_usage__from_unknown_usage_dict__only_completion_tokens__total_is_none():
    usage_data = {
        "completion_tokens": 100,
    }
    usage = OpikUsage.from_unknown_usage_dict(usage_data)
    assert usage.prompt_tokens is None
    assert usage.completion_tokens == 100
    assert usage.total_tokens is None


def test_opik_usage__from_unknown_usage_dict__empty_dict__all_none():
    usage = OpikUsage.from_unknown_usage_dict({})
    assert usage.prompt_tokens is None
    assert usage.completion_tokens is None
    assert usage.total_tokens is None


def test_opik_usage__to_backend_compatible_full_usage_dict__unknown_source__total_tokens_present():
    usage_data = {
        "prompt_tokens": 200,
        "completion_tokens": 100,
    }
    usage = OpikUsage.from_unknown_usage_dict(usage_data)
    full_dict = usage.to_backend_compatible_full_usage_dict()
    assert full_dict == {
        "completion_tokens": 100,
        "prompt_tokens": 200,
        "total_tokens": 300,
        "original_usage.prompt_tokens": 200,
        "original_usage.completion_tokens": 100,
    }


def test_opik_usage__from_unknown_usage_dict__string_tokens__coerced_to_int():
    usage_data = {
        "prompt_tokens": "200",
        "completion_tokens": "100",
    }
    usage = OpikUsage.from_unknown_usage_dict(usage_data)
    assert usage.prompt_tokens == 200
    assert usage.completion_tokens == 100
    assert usage.total_tokens == 300


def test_opik_usage__from_unknown_usage_dict__invalid_token_values__total_is_none():
    usage_data = {
        "prompt_tokens": "not-a-number",
        "completion_tokens": "also-invalid",
    }
    usage = OpikUsage.from_unknown_usage_dict(usage_data)
    assert usage.prompt_tokens is None
    assert usage.completion_tokens is None
    assert usage.total_tokens is None


def test_opik_usage__from_anthropic_dict__with_compaction_iterations__sums_all_iterations():
    # When compaction fires, top-level input/output_tokens reflect only the non-compaction
    # iterations (i.e. the message iterations). The compaction iteration is excluded from
    # the top-level but IS billed — summing all iterations gives the true billed cost.
    # https://platform.claude.com/docs/en/build-with-claude/compaction#understanding-usage
    usage_data = {
        # top-level = sum of non-compaction ("message") iterations only
        "input_tokens": 23000,
        "output_tokens": 1000,
        "cache_creation_input_tokens": 0,
        "cache_read_input_tokens": 0,
        "iterations": [
            {
                "type": "compaction",
                "input_tokens": 180000,
                "output_tokens": 3500,
                "cache_creation_input_tokens": 0,
                "cache_read_input_tokens": 0,
            },
            {
                "type": "message",
                "input_tokens": 23000,
                "output_tokens": 1000,
                "cache_creation_input_tokens": 0,
                "cache_read_input_tokens": 0,
            },
        ],
    }
    usage = OpikUsage.from_anthropic_dict(usage_data)
    assert usage.prompt_tokens == 203000  # 180000 + 23000
    assert usage.completion_tokens == 4500  # 3500 + 1000
    assert usage.total_tokens == 207500


def test_opik_usage__from_anthropic_dict__compaction_with_caching__includes_cache_tokens_per_iteration():
    # When both compaction and prompt caching are active, each iteration always carries
    # cache_creation_input_tokens and cache_read_input_tokens (required fields per SDK types).
    # top-level tokens reflect only the non-compaction iterations.
    usage_data = {
        # top-level = message iteration only: input=23000, cache_read=5000
        "input_tokens": 23000,
        "output_tokens": 1000,
        "cache_creation_input_tokens": 500,
        "cache_read_input_tokens": 5000,
        "iterations": [
            {
                "type": "compaction",
                "input_tokens": 180000,
                "output_tokens": 3500,
                "cache_read_input_tokens": 10000,
                "cache_creation_input_tokens": 2000,
            },
            {
                "type": "message",
                "input_tokens": 23000,
                "output_tokens": 1000,
                "cache_read_input_tokens": 5000,
                "cache_creation_input_tokens": 500,
            },
        ],
    }
    usage = OpikUsage.from_anthropic_dict(usage_data)
    assert usage.prompt_tokens == 220500  # (180000+10000+2000) + (23000+5000+500)
    assert usage.completion_tokens == 4500  # 3500 + 1000
    assert usage.total_tokens == 225000


def test_opik_usage__from_anthropic_dict__no_compaction__uses_top_level_tokens():
    usage_data = {
        "input_tokens": 200,
        "output_tokens": 100,
        "cache_creation_input_tokens": 50,
        "cache_read_input_tokens": 30,
    }
    usage = OpikUsage.from_anthropic_dict(usage_data)
    assert usage.prompt_tokens == 280  # 200 + 30 cache_read + 50 cache_creation
    assert usage.completion_tokens == 100
    assert usage.total_tokens == 380


def test_opik_usage__invalid_data_passed__validation_error_is_raised():
    usage_data = {"a": 123}
    with pytest.raises(pydantic.ValidationError):
        OpikUsage.from_openai_completions_dict(usage_data)
    with pytest.raises(pydantic.ValidationError):
        OpikUsage.from_google_dict(usage_data)
    with pytest.raises(pydantic.ValidationError):
        OpikUsage.from_anthropic_dict(usage_data)
