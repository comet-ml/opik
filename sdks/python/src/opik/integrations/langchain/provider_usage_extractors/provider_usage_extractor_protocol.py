from typing import Dict, Any
from typing_extensions import Protocol
from opik import llm_usage
import opik


class ProviderUsageExtractorProtocol(Protocol):
    """
    A protocol for extracting usage information from a provider run.

    Implement this protocol to extract usage information from a specific provider langchain Run.
    """

    PROVIDER: opik.LLMProvider
    """
    The provider that this extractor is for.
    """

    def is_provider_run(self, run_dict: Dict[str, Any]) -> bool:
        """
        Returns True if the run is from the provider that this extractor is for.
        """
        ...

    def get_llm_usage_info(self, run_dict: Dict[str, Any]) -> llm_usage.LLMUsageInfo:
        """
        Extracts usage information from the run.
        """
        ...
