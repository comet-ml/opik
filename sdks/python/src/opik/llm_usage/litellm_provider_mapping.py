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
    """Resolve the provider for a LiteLLM-style ``"<provider>/<model>"`` string.

    Returns the mapped :class:`LLMProvider` for known prefixes, the raw prefix
    string for unknown ones (so the backend can still attempt a lookup), or
    ``None`` when the model has no prefix.
    """
    if not model or "/" not in model:
        return None
    prefix = model.split("/", 1)[0]
    return LITELLM_PROVIDER_MAPPING.get(prefix, prefix)
