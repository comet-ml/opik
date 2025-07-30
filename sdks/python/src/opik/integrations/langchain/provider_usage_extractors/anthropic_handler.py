import logging
from typing import TYPE_CHECKING, Any, Dict, Literal, Optional, Tuple, Union
import opik
from opik import llm_usage
from . import usage_extractor_protocol

if TYPE_CHECKING:
    from langchain_core.tracers.schemas import Run

LOGGER = logging.getLogger(__name__)

class AnthropicUsageExtractor(usage_extractor_protocol.ProviderUsageExtractorProtocol):
    PROVIDER = opik.LLMProvider.ANTHROPIC

    def is_provider_run(self, run_dict: Dict[str, Any]) -> bool:
        try:
            if run_dict.get("serialized") is None:
                return False

            serialized_kwargs = run_dict.get("serialized", {}).get("kwargs", {})
            has_anthropic_key = "anthropic_api_key" in serialized_kwargs

            return has_anthropic_key

        except Exception:
            LOGGER.debug(
                "Failed to check if Run instance is from Anthropic LLM, returning False.",
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
            usage_dict = {
                "input_tokens": usage_dict["input_tokens"],
                "output_tokens": usage_dict["output_tokens"],
                "cache_creation_input_tokens": usage_dict["input_token_details"][
                    "cache_creation"
                ],
                "cache_read_input_tokens": usage_dict["input_token_details"][
                    "cache_read"
                ],
            }

        opik_usage = llm_usage.OpikUsage.from_anthropic_dict(usage_dict)
        return opik_usage
    except Exception:
        LOGGER.warning(
            "Failed to extract token usage from presumably Anthropic LLM langchain run.",
            exc_info=True,
        )
        return None


def _try_get_model_name(run_dict: Dict[str, Any]) -> Optional[str]:
    POSSIBLE_MODEL_NAME_KEYS = [
        "model",  # detected in langchain-anthropic 0.3.5
        "model_name",  # detected in langchain-anthropic 0.3.17
    ]
    model = None
    for model_name_key in POSSIBLE_MODEL_NAME_KEYS:
        try:
            if run_dict["outputs"]["llm_output"] is not None:
                model = run_dict["outputs"]["llm_output"][model_name_key]
            else:
                # Handle the streaming mode
                model = run_dict["outputs"]["generations"][-1][-1]["message"]["kwargs"][
                    "response_metadata"
                ][model_name_key]
        except KeyError:
            continue

    LOGGER.error(
        "Failed to extract model name from presumably Anthropic LLM langchain Run object: %s",
        run_dict,
    )
    return None
