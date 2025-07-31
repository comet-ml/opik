import logging
from typing import TYPE_CHECKING, Any, Dict, Optional

import opik
from opik import _logging as opik_logging
from opik import llm_usage, logging_messages
from . import provider_usage_extractor_protocol, langchain_run_helpers

if TYPE_CHECKING:
    pass


LOGGER = logging.getLogger(__name__)


class GroqUsageExtractor(
    provider_usage_extractor_protocol.ProviderUsageExtractorProtocol
):
    PROVIDER = opik.LLMProvider.GROQ

    def is_provider_run(self, run_dict: Dict[str, Any]) -> bool:
        try:
            if run_dict.get("serialized") is None:
                return False

            serialized_kwargs = run_dict["serialized"].get("kwargs", {})
            has_groq_key = "groq_api_key" in serialized_kwargs

            return has_groq_key

        except Exception:
            LOGGER.debug(
                "Failed to check if Run instance is from Groq LLM, returning False.",
                exc_info=True,
            )
            return False

    def get_llm_usage_info(self, run_dict: Dict[str, Any]) -> llm_usage.LLMUsageInfo:
        opik_usage = _try_get_token_usage(run_dict)
        model = _try_get_model_name(run_dict)

        return llm_usage.LLMUsageInfo(
            provider=self.PROVIDER, model=model, usage=opik_usage
        )


def _try_get_token_usage(run_dict: Dict[str, Any]) -> Optional[llm_usage.OpikUsage]:
    """
    Attempts to extract and return the token usage from the given run dictionary.

    Depending on the execution type (invoke, streaming mode, async, etc.), or even the model name itself,
    token usage info might be in different places, different formats, or completely missing.
    """
    try:
        if run_dict["outputs"]["llm_output"] is not None:
            token_usage_dict = run_dict["outputs"]["llm_output"]["token_usage"]
            if not isinstance(token_usage_dict, dict):
                return None
            return llm_usage.OpikUsage.from_groq_completions_dict(token_usage_dict)

        # streaming mode handling
        # token usage data MAY be available at the end of streaming
        # in async mode may not provide token usage info
        if (
            langchain_usage := langchain_run_helpers.try_get_streaming_token_usage(
                run_dict
            )
        ) is not None:
            groq_usage_dict = langchain_usage.map_to_groq_completions_usage()
            opik_usage = llm_usage.OpikUsage.from_groq_completions_dict(groq_usage_dict)
            return opik_usage

        opik_logging.log_once_at_level(
            logging_level=logging.WARNING,
            message=logging_messages.WARNING_TOKEN_USAGE_DATA_IS_NOT_AVAILABLE,
            logger=LOGGER,
        )
        return None

    except Exception:
        LOGGER.warning(
            logging_messages.FAILED_TO_EXTRACT_TOKEN_USAGE_FROM_PRESUMABLY_LANGCHAIN_GROQ_LLM_RUN,
            exc_info=True,
        )
        return None


def _try_get_model_name(run_dict: Dict[str, Any]) -> Optional[str]:
    model = run_dict["extra"].get("metadata", {}).get("ls_model_name")
    if model is not None:
        model = model.split("/")[-1]

    return model
