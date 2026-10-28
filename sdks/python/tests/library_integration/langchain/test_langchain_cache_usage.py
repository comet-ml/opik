"""langchain-anthropic and langchain-aws fold cache tokens into input_tokens.

Opik maps that usage onto the provider's own fields, where the uncached input
excludes the cache, so the mapping must take the cache back out or it is counted
twice.
"""

from typing import Any, Dict

import pytest

from opik.integrations.langchain.provider_usage_extractors import (
    anthropic_usage_extractor,
    anthropic_vertexai_usage_extractor,
    bedrock_usage_extractor,
)

# The provider reported 10 uncached input tokens in every case below.
CACHE_CASES = [
    pytest.param({"cache_read": 4000, "cache_creation": 0}, 4000, 0, id="cache-read"),
    pytest.param(
        {
            "cache_read": 1000,
            "cache_creation": 0,
            "ephemeral_1h_input_tokens": 3000,
        },
        1000,
        3000,
        id="ttl-cache-write",
    ),
]


def _usage_metadata(input_token_details: Dict[str, Any], cached: int) -> Dict[str, Any]:
    return {
        "input_tokens": 10 + cached,
        "output_tokens": 5,
        "total_tokens": 15 + cached,
        "input_token_details": input_token_details,
    }


def _run(serialized: Dict[str, Any], usage_metadata: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "serialized": serialized,
        "outputs": {
            "llm_output": {"model_name": "claude-sonnet-4-5"},
            "generations": [
                [{"message": {"kwargs": {"usage_metadata": usage_metadata}}}]
            ],
        },
    }


@pytest.mark.parametrize("details, cache_read, cache_creation", CACHE_CASES)
def test_anthropic_usage__langchain_cache_tokens__counted_once(
    details, cache_read, cache_creation
):
    cached = cache_read + cache_creation
    run = _run(
        {"kwargs": {"anthropic_api_key": "fixture-value"}},
        _usage_metadata(details, cached),
    )

    usage = (
        anthropic_usage_extractor.AnthropicUsageExtractor()
        .get_llm_usage_info(run)
        .usage
    )

    assert usage.prompt_tokens == 10 + cached
    assert usage.provider_usage.input_tokens == 10
    assert usage.provider_usage.cache_read_input_tokens == cache_read
    assert usage.provider_usage.cache_creation_input_tokens == cache_creation


@pytest.mark.parametrize("details, cache_read, cache_creation", CACHE_CASES)
def test_anthropic_vertexai_usage__langchain_cache_tokens__counted_once(
    details, cache_read, cache_creation
):
    # ChatAnthropicVertex adds the cache into input_tokens like langchain-anthropic.
    cached = cache_read + cache_creation
    run = _run(
        {"id": ["langchain", "chat_models", "vertexai", "ChatAnthropicVertex"]},
        _usage_metadata(details, cached),
    )
    run["extra"] = {
        "invocation_params": {
            "_type": "anthropic-chat-vertexai",
            "model_name": "claude-sonnet-4-5",
        }
    }
    extractor = anthropic_vertexai_usage_extractor.AnthropicVertexAIUsageExtractor()
    assert extractor.is_provider_run(run)

    usage = extractor.get_llm_usage_info(run).usage

    assert usage.prompt_tokens == 10 + cached
    assert usage.provider_usage.input_tokens == 10
    assert usage.provider_usage.cache_read_input_tokens == cache_read
    assert usage.provider_usage.cache_creation_input_tokens == cache_creation


CONVERSE_SERIALIZED = [
    pytest.param(
        {"id": ["langchain_aws", "chat_models", "ChatBedrockConverse"]},
        id="ChatBedrockConverse",
    ),
    pytest.param(
        {
            "id": ["langchain", "chat_models", "bedrock", "ChatBedrock"],
            "kwargs": {"beta_use_converse_api": True},
        },
        id="ChatBedrock-converse",
    ),
]


@pytest.mark.parametrize("serialized", CONVERSE_SERIALIZED)
@pytest.mark.parametrize("details, cache_read, cache_creation", CACHE_CASES)
def test_bedrock_usage__converse_cache_tokens__counted_once(
    serialized, details, cache_read, cache_creation
):
    cached = cache_read + cache_creation
    run = _run(serialized, _usage_metadata(details, cached))

    usage = (
        bedrock_usage_extractor.BedrockUsageExtractor().get_llm_usage_info(run).usage
    )

    assert usage.prompt_tokens == 10 + cached
    assert usage.provider_usage.inputTokens == 10
    assert usage.provider_usage.cacheReadInputTokens == cache_read
    assert usage.provider_usage.cacheWriteInputTokens == cache_creation


TTL_SPLIT_DETAILS = {
    "cache_read": 1000,
    "cache_creation": 0,
    "ephemeral_5m_input_tokens": 1000,
    "ephemeral_1h_input_tokens": 2000,
}


def test_anthropic_usage__langchain_ttl_cache_writes__split_kept_by_ttl():
    run = _run(
        {"kwargs": {"anthropic_api_key": "fixture-value"}},
        _usage_metadata(TTL_SPLIT_DETAILS, 4000),
    )

    usage = (
        anthropic_usage_extractor.AnthropicUsageExtractor()
        .get_llm_usage_info(run)
        .usage
    )

    full_dict = usage.to_backend_compatible_full_usage_dict()
    assert usage.provider_usage.cache_creation_input_tokens == 3000
    assert full_dict["original_usage.cache_creation.ephemeral_5m_input_tokens"] == 1000
    assert full_dict["original_usage.cache_creation.ephemeral_1h_input_tokens"] == 2000


@pytest.mark.parametrize("serialized", CONVERSE_SERIALIZED)
def test_bedrock_usage__langchain_ttl_cache_writes__split_kept_by_ttl(serialized):
    run = _run(serialized, _usage_metadata(TTL_SPLIT_DETAILS, 4000))

    usage = (
        bedrock_usage_extractor.BedrockUsageExtractor().get_llm_usage_info(run).usage
    )

    full_dict = usage.to_backend_compatible_full_usage_dict()
    assert usage.provider_usage.cacheWriteInputTokens == 3000
    assert full_dict["original_usage.cacheDetails.5m.inputTokens"] == 1000
    assert full_dict["original_usage.cacheDetails.1h.inputTokens"] == 2000


def test_bedrock_usage__legacy_chat_bedrock__input_tokens_kept():
    # invoke_model reports the uncached count in input_tokens, cache beside it.
    run = _run(
        {
            "id": ["langchain", "chat_models", "bedrock", "ChatBedrock"],
            "kwargs": {"model_id": "anthropic.claude-sonnet-4-5"},
        },
        {
            "input_tokens": 10,
            "output_tokens": 5,
            "total_tokens": 15,
            "input_token_details": {"cache_read": 4000, "cache_creation": 0},
        },
    )

    usage = (
        bedrock_usage_extractor.BedrockUsageExtractor().get_llm_usage_info(run).usage
    )

    assert usage.provider_usage.inputTokens == 10
    assert usage.provider_usage.cacheReadInputTokens == 4000
    assert usage.prompt_tokens == 4010


def test_anthropic_usage__no_cache_details__input_tokens_unchanged():
    run = _run(
        {"kwargs": {"anthropic_api_key": "fixture-value"}},
        {"input_tokens": 12, "output_tokens": 5, "total_tokens": 17},
    )

    usage = (
        anthropic_usage_extractor.AnthropicUsageExtractor()
        .get_llm_usage_info(run)
        .usage
    )

    assert usage.prompt_tokens == 12
    assert usage.provider_usage.input_tokens == 12
