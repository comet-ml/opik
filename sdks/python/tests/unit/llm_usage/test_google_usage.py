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


def test_google_gemini_usage_creation__no_candidates_key__candidates_token_count_is_None():
    # Gemini leaves candidates_token_count out when nothing was generated (e.g. a
    # blocked prompt), and ADK dumps usage with exclude_unset, so the key is absent.
    usage_data = {"prompt_token_count": 10, "total_token_count": 10}

    usage = GoogleGeminiUsage.from_original_usage_dict(usage_data)

    assert usage.candidates_token_count is None
    assert usage.prompt_token_count == 10


def test_opik_usage__from_google_dict__no_candidates_key__completion_tokens_zero():
    from opik.llm_usage.opik_usage import OpikUsage

    usage = OpikUsage.from_google_dict(
        {"prompt_token_count": 10, "total_token_count": 10}
    )

    assert usage.completion_tokens == 0
    assert usage.prompt_tokens == 10


@pytest.mark.parametrize("provider", ["google_ai", "google_vertexai"])
def test_build_opik_usage__google__no_candidates_key__completion_tokens_zero(provider):
    from opik import llm_usage
    from opik.types import LLMProvider

    usage = llm_usage.build_opik_usage(
        provider=LLMProvider(provider),
        usage={"prompt_token_count": 10, "total_token_count": 10},
    )

    assert usage.completion_tokens == 0
    assert usage.prompt_tokens == 10


# `total_token_count` is the sum of prompt, candidates, tool-use prompt and
# thoughts tokens. `tool_use_prompt_token_count` holds the tool results "which are
# provided back to the model as input", so it belongs on the input side, while
# `prompt_token_count` only covers the original prompt. Counting it is the same
# treatment Anthropic and Bedrock already get for their extra input counters.
TOOL_USE_PAYLOADS = [
    pytest.param(
        # The usage_metadata sample from Google's URL-context documentation.
        {
            "prompt_token_count": 27,
            "candidates_token_count": 45,
            "thoughts_token_count": 31,
            "tool_use_prompt_token_count": 10309,
            "total_token_count": 10412,
        },
        27 + 10309,
        45 + 31,
        id="tool_use_with_thoughts",
    ),
    pytest.param(
        {
            "prompt_token_count": 1041,
            "candidates_token_count": 902,
            "tool_use_prompt_token_count": 377545,
            "total_token_count": 379488,
        },
        1041 + 377545,
        902,
        id="tool_use_without_thoughts",
    ),
]


@pytest.mark.parametrize(
    "usage_data,expected_prompt,expected_completion", TOOL_USE_PAYLOADS
)
def test_opik_usage__from_google_dict__tool_use_prompt_tokens__counted_as_prompt(
    usage_data, expected_prompt, expected_completion
):
    from opik.llm_usage.opik_usage import OpikUsage

    usage = OpikUsage.from_google_dict(dict(usage_data))

    assert usage.prompt_tokens == expected_prompt
    assert usage.completion_tokens == expected_completion
    # The split has to add up to the total the provider reported.
    assert usage.prompt_tokens + usage.completion_tokens == usage.total_tokens
    # The counter is still available unadjusted on the original usage.
    assert (
        usage.provider_usage.tool_use_prompt_token_count
        == usage_data["tool_use_prompt_token_count"]
    )


@pytest.mark.parametrize(
    "usage_data,expected_prompt,expected_completion", TOOL_USE_PAYLOADS
)
def test_build_opik_usage__google__tool_use_prompt_tokens__counted_as_prompt(
    usage_data, expected_prompt, expected_completion
):
    from opik import llm_usage
    from opik.types import LLMProvider

    usage = llm_usage.build_opik_usage(
        provider=LLMProvider.GOOGLE_AI, usage=dict(usage_data)
    )

    assert usage.prompt_tokens == expected_prompt
    assert usage.completion_tokens == expected_completion
    assert usage.prompt_tokens + usage.completion_tokens == usage.total_tokens


@pytest.mark.parametrize(
    "usage_data,expected_prompt,expected_completion",
    [
        pytest.param(
            {
                "prompt_token_count": 200,
                "candidates_token_count": 100,
                "total_token_count": 300,
            },
            200,
            100,
            id="plain",
        ),
        pytest.param(
            {
                "prompt_token_count": 1000,
                "candidates_token_count": 500,
                "thoughts_token_count": 500,
                "total_token_count": 2000,
            },
            1000,
            1000,
            id="thinking_only",
        ),
        pytest.param(
            {
                "prompt_token_count": 200,
                "candidates_token_count": 100,
                "tool_use_prompt_token_count": 0,
                "total_token_count": 300,
            },
            200,
            100,
            id="tool_use_explicitly_zero",
        ),
        pytest.param(
            {
                "prompt_token_count": 200,
                "candidates_token_count": 100,
                "cached_content_token_count": 50,
                "total_token_count": 300,
            },
            200,
            100,
            id="cached_content_is_a_subset_and_is_not_added",
        ),
    ],
)
def test_opik_usage__from_google_dict__no_tool_use__unchanged(
    usage_data, expected_prompt, expected_completion
):
    # The four shapes that were already correct must stay correct: a falsy
    # `tool_use_prompt_token_count` of 0, an absent one, a thinking-only call, and a
    # cached-content call (cached content is a documented subset of the prompt
    # count, so adding it would double count).
    from opik.llm_usage.opik_usage import OpikUsage

    usage = OpikUsage.from_google_dict(dict(usage_data))

    assert usage.prompt_tokens == expected_prompt
    assert usage.completion_tokens == expected_completion
    assert usage.prompt_tokens + usage.completion_tokens == usage.total_tokens


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
