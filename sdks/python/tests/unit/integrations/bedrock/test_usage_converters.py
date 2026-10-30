from opik.integrations.bedrock.invoke_model import usage_converters


def test_anthropic_to_bedrock_usage__claude_splits_cache_writes_by_ttl__split_kept_as_cache_details():
    bedrock_usage = usage_converters.anthropic_to_bedrock_usage(
        {
            "input_tokens": 12,
            "output_tokens": 7,
            "cache_creation_input_tokens": 1024,
            "cache_read_input_tokens": 4096,
            "cache_creation": {
                "ephemeral_5m_input_tokens": 768,
                "ephemeral_1h_input_tokens": 256,
            },
        }
    )

    assert bedrock_usage["cacheWriteInputTokens"] == 1024
    assert bedrock_usage["cacheDetails"] == {
        "5m": {"inputTokens": 768},
        "1h": {"inputTokens": 256},
    }


def test_anthropic_to_bedrock_usage__no_ttl_split__no_cache_details():
    bedrock_usage = usage_converters.anthropic_to_bedrock_usage(
        {"input_tokens": 12, "output_tokens": 7, "cache_creation_input_tokens": 1024}
    )

    assert "cacheDetails" not in bedrock_usage
