from typing import Dict, Any
from typing_extensions import Protocol
from opik import llm_usage
import opik

class ProviderUsageExtractorProtocol(Protocol):
    PROVIDER: opik.LLMProvider
    
    def is_provider_run(self, run_dict: Dict[str, Any]) -> bool:
        ...

    def get_llm_usage_info(self, run_dict: Dict[str, Any]) -> llm_usage.LLMUsageInfo:
        ...