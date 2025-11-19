import opik
from opik import llm_usage


def test_opik_usage_factory__openai_happyflow():
    result = llm_usage.build_opik_usage(
        provider=opik.LLMProvider.OPENAI,
        usage={"completion_tokens": 10, "prompt_tokens": 20, "total_tokens": 30},
    )

    assert result.completion_tokens == 10
    assert result.prompt_tokens == 20
    assert result.total_tokens == 30

    assert result.provider_usage.completion_tokens == 10
    assert result.provider_usage.prompt_tokens == 20
    assert result.provider_usage.total_tokens == 30


def test_opik_usage_factory__anthropic_happyflow():
    result = llm_usage.build_opik_usage(
        provider=opik.LLMProvider.ANTHROPIC,
        usage={"input_tokens": 10, "output_tokens": 20},
    )

    assert result.completion_tokens == 20
    assert result.prompt_tokens == 10
    assert result.total_tokens == 30

    assert result.provider_usage.input_tokens == 10
    assert result.provider_usage.output_tokens == 20


def test_opik_usage_factory__vertex_ai_none_candidates_token_count__happy_flow():
    result = llm_usage.build_opik_usage(
        provider=opik.LLMProvider.GOOGLE_VERTEXAI,
        usage={
            "cached_content_token_count": None,
            "candidates_token_count": None,
            "prompt_token_count": 7859,
            "thoughts_token_count": None,
            "total_token_count": 7859,
        },
    )

    assert result.completion_tokens == 0
    assert result.prompt_tokens == 7859
    assert result.total_tokens == 7859
