import pytest

from opik.llm_usage import litellm_provider_mapping
from opik.types import LLMProvider


@pytest.mark.parametrize(
    "model,expected_provider",
    [
        ("gemini/gemini-3.1-flash-lite-preview", LLMProvider.GOOGLE_AI),
        ("vertex_ai/gemini-2.5-pro", LLMProvider.GOOGLE_VERTEXAI),
        ("vertex_ai-language-models/gemini-1.5-pro", LLMProvider.GOOGLE_VERTEXAI),
        (
            "vertex_ai-anthropic_models/claude-3-5-sonnet",
            LLMProvider.ANTHROPIC_VERTEXAI,
        ),
        ("anthropic/claude-3-5-sonnet", LLMProvider.ANTHROPIC),
        ("bedrock/anthropic.claude-3-haiku", LLMProvider.BEDROCK),
        ("bedrock_converse/anthropic.claude-3-haiku", LLMProvider.BEDROCK),
        ("groq/llama-3.1-70b-versatile", LLMProvider.GROQ),
        ("openai/gpt-4o", LLMProvider.OPENAI),
    ],
)
def test_infer_provider_from_litellm_model_prefix__known_prefix__returns_provider_enum(
    model, expected_provider
):
    assert (
        litellm_provider_mapping.infer_provider_from_litellm_model_prefix(model)
        == expected_provider
    )


@pytest.mark.parametrize(
    "model,expected_raw_prefix",
    [
        ("cohere/command-r", "cohere"),
        ("mistralai/mixtral-8x7b", "mistralai"),
        ("perplexity/sonar-medium-online", "perplexity"),
        ("unknown_provider/some-model", "unknown_provider"),
    ],
)
def test_infer_provider_from_litellm_model_prefix__unknown_prefix__returns_raw_prefix_string(
    model, expected_raw_prefix
):
    assert (
        litellm_provider_mapping.infer_provider_from_litellm_model_prefix(model)
        == expected_raw_prefix
    )


@pytest.mark.parametrize(
    "model",
    [
        None,
        "",
        "gpt-4o",
        "claude-3-5-sonnet",
        "some-standalone-model-name",
    ],
)
def test_infer_provider_from_litellm_model_prefix__no_prefix__returns_none(model):
    assert (
        litellm_provider_mapping.infer_provider_from_litellm_model_prefix(model) is None
    )
