import logging
from typing import TYPE_CHECKING, Any, Dict, Optional
import opik
from opik import llm_usage, _logging as opik_logging, logging_messages
from . import provider_usage_extractor_protocol
from . import langchain_run_helpers
from .langchain_run_helpers import langchain_usage

if TYPE_CHECKING:
    pass

LOGGER = logging.getLogger(__name__)


class AnthropicUsageExtractor(
    provider_usage_extractor_protocol.ProviderUsageExtractorProtocol
):
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

    def get_llm_usage_info(self, run_dict: Dict[str, Any]) -> llm_usage.LLMUsageInfo:
        usage_dict = _try_get_token_usage(run_dict)
        model = _try_get_model_name(run_dict)

        return llm_usage.LLMUsageInfo(
            provider=self.PROVIDER, model=model, usage=usage_dict
        )


def _try_get_token_usage(run_dict: Dict[str, Any]) -> Optional[llm_usage.OpikUsage]:
    try:
        if token_usage := langchain_run_helpers.try_to_get_usage_by_search(
            run_dict, candidate_keys=None
        ):
            if isinstance(token_usage, langchain_usage.LangChainUsage):
                anthropic_usage_dict = token_usage.map_to_anthropic_usage()
                return llm_usage.OpikUsage.from_anthropic_dict(anthropic_usage_dict)

        opik_logging.log_once_at_level(
            logging.WARNING,
            "Failed to extract token usage from presumably Anthropic LLM langchain run. Run dict: %s",
            LOGGER,
            run_dict,
        )

        opik_logging.log_once_at_level(
            logging_level=logging.WARNING,
            message=logging_messages.WARNING_TOKEN_USAGE_DATA_IS_NOT_AVAILABLE,
            logger=LOGGER,
        )

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

    if model is None:
        LOGGER.error(
            "Failed to extract model name from presumably Anthropic LLM langchain Run object: %s",
            run_dict,
        )

    return model
