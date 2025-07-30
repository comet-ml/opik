import logging
from typing import TYPE_CHECKING, Any, Dict, Literal, Optional, Tuple, Union

import opik
from opik import llm_usage
from . import usage_extractor_protocol

if TYPE_CHECKING:
    from langchain_core.tracers.schemas import Run

LOGGER = logging.getLogger(__name__)

class AnthropicVertexAIUsageExtractor(usage_extractor_protocol.ProviderUsageExtractorProtocol):
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


    def get_llm_usage_info(
        self, run_dict: Dict[str, Any]
    ) -> llm_usage.LLMUsageInfo:
        usage_dict = _try_get_token_usage(run_dict)
        model = _try_get_model_name(run_dict)

        return llm_usage.LLMUsageInfo(provider=self.PROVIDER, model=model, usage=usage_dict)


def _try_get_token_usage(run_dict: Dict[str, Any]) -> Optional[llm_usage.OpikUsage]:
    try:
        if run_dict["outputs"]["llm_output"] is not None:
            usage_dict = run_dict["outputs"]["llm_output"]["usage"]
        else:
            # Handle the streaming mode
            usage_dict = run_dict["outputs"]["generations"][-1][-1]["message"][
                "kwargs"
            ]["usage_metadata"]

            # Support streaming mode
            if "input_token_details" in usage_dict:
                cache_creation_input_tokens = usage_dict["input_token_details"][
                    "cache_creation"
                ]
                cache_read_input_tokens = usage_dict["input_token_details"][
                    "cache_read"
                ]
            else:
                cache_creation_input_tokens = None
                cache_read_input_tokens = None

            usage_dict = {
                "input_tokens": usage_dict["input_tokens"],
                "output_tokens": usage_dict["output_tokens"],
                "cache_creation_input_tokens": cache_creation_input_tokens,
                "cache_read_input_tokens": cache_read_input_tokens,
            }

        opik_usage = llm_usage.OpikUsage.from_anthropic_dict(usage_dict)
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
