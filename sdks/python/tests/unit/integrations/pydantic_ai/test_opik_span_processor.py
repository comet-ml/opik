from opik.integrations.pydantic_ai.opik_span_processor import _token_usage, _try_json


def test_token_usage__no_tokens__returns_none():
    assert _token_usage({}) is None


def test_token_usage__plain_tokens__prompt_completion_total():
    usage = _token_usage(
        {"gen_ai.usage.input_tokens": 51, "gen_ai.usage.output_tokens": 4}
    )
    assert usage == {
        "prompt_tokens": 51,
        "completion_tokens": 4,
        "total_tokens": 55,
    }


def test_token_usage__with_cache__anthropic_shape_without_double_counting():
    # pydantic-ai reports input_tokens INCLUDING the cached prefix; we emit the
    # fresh portion so Opik's AnthropicUsage parser does not double count.
    usage = _token_usage(
        {
            "gen_ai.usage.input_tokens": 100,
            "gen_ai.usage.output_tokens": 10,
            "gen_ai.usage.cache_read.input_tokens": 30,
            "gen_ai.usage.cache_creation.input_tokens": 20,
        }
    )
    assert usage == {
        "input_tokens": 50,
        "output_tokens": 10,
        "cache_read_input_tokens": 30,
        "cache_creation_input_tokens": 20,
    }


def test_try_json__valid_and_invalid():
    assert _try_json('{"a": 1}') == {"a": 1}
    assert _try_json("not json") == "not json"
    assert _try_json(42) == 42
