import logging
from typing import Any, Dict, List, Optional

from opik import llm_usage
from . import (
    openai_handler,
    anthropic_handler,
    google_generative_ai_handler,
    vertexai_handler,
    groq_handler,
    anthropic_vertexai_handler,
)
from .usage_extractor_protocol import ProviderUsageExtractorProtocol

LOGGER = logging.getLogger(__name__)

_REGISTERED_PROVIDER_USAGE_EXTRACTORS: List[ProviderUsageExtractorProtocol] = [
    openai_handler.OpenAIUsageExtractor(),
    anthropic_handler.AnthropicUsageExtractor(),
    google_generative_ai_handler.GoogleGenerativeAIUsageExtractor(),
    vertexai_handler.VertexAIUsageExtractor(),
    groq_handler.GroqUsageExtractor(),
    anthropic_vertexai_handler.AnthropicVertexAIUsageExtractor(),
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
