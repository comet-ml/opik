"""
Provider usage extractors with API enforcement.

This package provides a validated registry of provider handlers with
programmatically enforced APIs for extracting usage information from
LangChain runs.
"""

from .usage_extractor_protocol import ProviderUsageExtractorProtocol
from .usage_extractor import (
    extract_llm_usage_info,
    get_provider_extractor,
    PROVIDER_USAGE_EXTRACTORS,
)

# Import all the extractor classes for direct access
from .openai_handler import OpenAIUsageExtractor
from .anthropic_handler import AnthropicUsageExtractor
from .google_generative_ai_handler import GoogleGenerativeAIUsageExtractor
from .vertexai_handler import VertexAIUsageExtractor
from .groq_handler import GroqUsageExtractor
from .anthropic_vertexai_handler import AnthropicVertexAIUsageExtractor

__all__ = [
    # Core API
    "extract_llm_usage_info",
    "get_provider_extractor",
    "PROVIDER_USAGE_EXTRACTORS",
    
    # Protocol
    "ProviderUsageExtractorProtocol",
    
    # Individual extractors
    "OpenAIUsageExtractor",
    "AnthropicUsageExtractor",
    "GoogleGenerativeAIUsageExtractor", 
    "VertexAIUsageExtractor",
    "GroqUsageExtractor",
    "AnthropicVertexAIUsageExtractor",
]
