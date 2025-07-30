import logging
from typing import TYPE_CHECKING, Any, Dict, Optional

import opik
from opik import llm_usage
from . import provider_usage_extractor_protocol
from . import langchain_run_helpers

if TYPE_CHECKING:
    pass

LOGGER = logging.getLogger(__name__)


class AnthropicVertexAIUsageExtractor(
    provider_usage_extractor_protocol.ProviderUsageExtractorProtocol
):
    PROVIDER = opik.LLMProvider.ANTHROPIC_VERTEXAI

    def is_provider_run(self, run_dict: Dict[str, Any]) -> bool:
        try:
            if run_dict.get("serialized") is None:
                return False

            invocation_params = run_dict["extra"].get("invocation_params", {})
            provider = invocation_params.get("_type", "").lower()
            is_anthropic_vertexai = (
                "vertexai" in provider.lower() and "anthropic" in provider.lower()
            )

            return is_anthropic_vertexai

        except Exception:
            LOGGER.debug(
                "Failed to check if Run instance is from Anthropic LLM vertexai, returning False.",
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
        anthropic_usage_dict = langchain_usage.map_to_anthropic_usage()

        opik_usage = llm_usage.OpikUsage.from_anthropic_dict(anthropic_usage_dict)
        return opik_usage
    except Exception:
        LOGGER.warning(
            "Failed to extract token usage from presumably Anthropic LLM vertexai langchain run.",
            exc_info=True,
        )
        return None


def _try_get_model_name(run_dict: Dict[str, Any]) -> Optional[str]:
    if invocation_params := run_dict["extra"].get("invocation_params"):
        return invocation_params.get("model_name")
    return None
