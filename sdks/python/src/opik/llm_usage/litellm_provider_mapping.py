from typing import Dict, Optional, Union

from opik.types import LLMProvider

LITELLM_PROVIDER_MAPPING: Dict[str, LLMProvider] = {
    "openai": LLMProvider.OPENAI,
    "vertex_ai": LLMProvider.GOOGLE_VERTEXAI,
    "vertex_ai-language-models": LLMProvider.GOOGLE_VERTEXAI,
    "vertex_ai-anthropic_models": LLMProvider.ANTHROPIC_VERTEXAI,
    "gemini": LLMProvider.GOOGLE_AI,
    "anthropic": LLMProvider.ANTHROPIC,
    "bedrock": LLMProvider.BEDROCK,
    "bedrock_converse": LLMProvider.BEDROCK,
    "groq": LLMProvider.GROQ,
}


def infer_provider_from_litellm_model_prefix(
    model: Optional[str],
) -> Optional[Union[LLMProvider, str]]:
    """Infer a provider from a LiteLLM-style "<provider>/<model>" string.

    Returns:
    - an `LLMProvider` enum value when the prefix is in the known mapping,
    - the raw prefix string when a prefix is present but not mapped (callers can
      forward it to the backend; unsupported providers resolve to zero cost
      gracefully, and this preserves the original attribution for future
      catalog updates),
    - `None` when the input is empty or has no prefix (callers typically fall
      back to `LLMProvider.OPENAI` for the native-OpenAI path).

    Unlike `litellm.get_llm_provider`, this does not require the `litellm` package.
    """
    if not model or "/" not in model:
        return None
    prefix = model.split("/", 1)[0]
    return LITELLM_PROVIDER_MAPPING.get(prefix, prefix)
