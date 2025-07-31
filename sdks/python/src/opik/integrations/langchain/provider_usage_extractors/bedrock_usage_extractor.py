import logging
from typing import TYPE_CHECKING, Any, Dict, Optional

import opik
from opik import llm_usage
from . import provider_usage_extractor_protocol
from . import langchain_run_helpers

if TYPE_CHECKING:
    pass

LOGGER = logging.getLogger(__name__)


class BedrockUsageExtractor(
    provider_usage_extractor_protocol.ProviderUsageExtractorProtocol
):
    PROVIDER = opik.LLMProvider.BEDROCK

    def is_provider_run(self, run_dict: Dict[str, Any]) -> bool:
        try:
            if run_dict.get("serialized") is None:
                return False

            # Check for langchain_aws.ChatBedrock or other bedrock indicators
            invocation_params = run_dict.get("extra", {}).get("invocation_params", {})
            provider_class = run_dict.get("serialized", {}).get("id", [])
            
            # Check if it's a bedrock provider by looking at the class path
            is_bedrock = (
                "bedrock" in str(provider_class).lower() or
                "ChatBedrock" in str(provider_class) or
                any("bedrock" in str(param).lower() for param in invocation_params.values() if isinstance(param, str))
            )

            return is_bedrock

        except Exception:
            LOGGER.debug(
                "Failed to check if Run instance is from Bedrock LLM, returning False.",
                exc_info=True,
            )
            return False

    def get_llm_usage_info(self, run_dict: Dict[str, Any]) -> llm_usage.LLMUsageInfo:
        usage_dict = _try_get_token_usage(run_dict)
        model = _try_get_model_name(run_dict)

        return llm_usage.LLMUsageInfo(
            provider=self.PROVIDER, model=model, usage=usage_dict
        )


def _try_get_token_usage(run_dict: Dict[str, Any]) -> Optional[llm_usage.OpikUsage]:
    try:
        langchain_usage = langchain_run_helpers.try_get_token_usage(run_dict)
        bedrock_usage_dict = langchain_usage.map_to_bedrock_usage()

        opik_usage = llm_usage.OpikUsage.from_bedrock_dict(bedrock_usage_dict)
        return opik_usage
    except Exception:
        LOGGER.warning(
            "Failed to extract token usage from presumably Bedrock LLM langchain run.",
            exc_info=True,
        )
        return None


def _try_get_model_name(run_dict: Dict[str, Any]) -> Optional[str]:
    MODEL_NAME_KEY = "model_id"
    
    model = None

    invocation_params = run_dict.get("extra", {}).get("invocation_params", {})
    if MODEL_NAME_KEY in invocation_params:
        model = invocation_params[MODEL_NAME_KEY]

    if model is None:
        LOGGER.error(
            "Failed to extract model name from presumably Bedrock LLM langchain Run object: %s",
            run_dict,
        )

    return model 