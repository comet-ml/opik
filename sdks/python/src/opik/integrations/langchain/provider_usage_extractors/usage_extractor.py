import logging
from typing import Any, Dict, List, Optional

from opik import llm_usage
from . import (
    openai_usage_extractor,
    anthropic_usage_extractor,
    google_generative_ai_usage_extractor,
    vertexai_usage_extractor,
    groq_usage_extractor,
    anthropic_vertexai_usage_extractor,
    bedrock_usage_extractor,
)
from . import provider_usage_extractor_protocol

LOGGER = logging.getLogger(__name__)

_REGISTERED_PROVIDER_USAGE_EXTRACTORS: List[
    provider_usage_extractor_protocol.ProviderUsageExtractorProtocol
] = [
    openai_usage_extractor.OpenAIUsageExtractor(),
    anthropic_usage_extractor.AnthropicUsageExtractor(),
    google_generative_ai_usage_extractor.GoogleGenerativeAIUsageExtractor(),
    vertexai_usage_extractor.VertexAIUsageExtractor(),
    groq_usage_extractor.GroqUsageExtractor(),
    anthropic_vertexai_usage_extractor.AnthropicVertexAIUsageExtractor(),
    bedrock_usage_extractor.BedrockUsageExtractor(),
]


def try_extract_provider_usage_data(
    run_dict: Dict[str, Any],
) -> Optional[llm_usage.LLMUsageInfo]:
    for extractor in _REGISTERED_PROVIDER_USAGE_EXTRACTORS:
        if not extractor.is_provider_run(run_dict):
            continue

        try:
            return extractor.get_llm_usage_info(run_dict)
        except Exception:
            LOGGER.warning(
                "Failed to extract usage data for presumably %s provider.",
                extractor.PROVIDER,
                exc_info=True,
            )
            return None

    return None
