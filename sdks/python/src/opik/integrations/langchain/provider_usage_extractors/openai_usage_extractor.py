import logging
from typing import TYPE_CHECKING, Any, Dict, Optional
import opik
from opik import _logging as opik_logging
from opik import llm_usage, logging_messages
from . import provider_usage_extractor_protocol, langchain_run_helpers
from .langchain_run_helpers import langchain_usage

if TYPE_CHECKING:
    pass


LOGGER = logging.getLogger(__name__)


OPENAI_CANDIDATE_USAGE_KEYS = {"prompt_tokens", "completion_tokens", "total_tokens"}


class OpenAIUsageExtractor(
    provider_usage_extractor_protocol.ProviderUsageExtractorProtocol
):
    PROVIDER = opik.LLMProvider.OPENAI

    def is_provider_run(self, run_dict: Dict[str, Any]) -> bool:
        try:
            if run_dict.get("serialized") is None:
                return False

            serialized_kwargs = run_dict["serialized"].get("kwargs", {})
            has_openai_key = "openai_api_key" in serialized_kwargs

            return has_openai_key

        except Exception:
            LOGGER.debug(
                "Failed to check if Run instance is from OpenAI LLM, returning False.",
                exc_info=True,
            )
            return False

    def get_llm_usage_info(self, run_dict: Dict[str, Any]) -> llm_usage.LLMUsageInfo:
        opik_usage = _try_get_token_usage(run_dict)
        model = _try_get_model_name(run_dict)
        provider = self._get_provider(run_dict)

        return llm_usage.LLMUsageInfo(provider=provider, model=model, usage=opik_usage)

    def _get_provider(self, run_dict: Dict[str, Any]) -> str:
        """
        Returns "openai" unless the base url is different (in that case returns the base url)
        """
        provider = self.PROVIDER

        # Check base URL to detect custom providers
        if base_url := run_dict["extra"].get("invocation_params", {}).get("base_url"):
            if base_url.host != "api.openai.com":
                provider = base_url.host

        return provider


def _try_get_token_usage(run_dict: Dict[str, Any]) -> Optional[llm_usage.OpikUsage]:
    """
    Attempts to extract and return the token usage from the given run dictionary.

    Depending on the execution type (invoke, streaming mode, async, etc.), or even the model name itself,
    token usage info might be in different places, different formats, or completely missing.
    """
    try:
        if token_usage := langchain_run_helpers.try_to_get_usage_by_search(
            run_dict, OPENAI_CANDIDATE_USAGE_KEYS
        ):
            if isinstance(token_usage, dict):
                return llm_usage.OpikUsage.from_openai_completions_dict(token_usage)
            elif isinstance(token_usage, langchain_usage.LangChainUsage):
                openai_usage_dict = token_usage.map_to_openai_completions_usage()
                return llm_usage.OpikUsage.from_openai_completions_dict(
                    openai_usage_dict
                )

        opik_logging.log_once_at_level(
            logging.WARNING,
            logging_messages.FAILED_TO_EXTRACT_TOKEN_USAGE_FROM_PRESUMABLY_LANGCHAIN_OPENAI_LLM_RUN,
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
            logging_messages.FAILED_TO_EXTRACT_TOKEN_USAGE_FROM_PRESUMABLY_LANGCHAIN_OPENAI_LLM_RUN,
            run_dict,
            exc_info=True,
        )

    return None


def _try_get_model_name(run_dict: Dict[str, Any]) -> Optional[str]:
    """
    Extracts the model name from the run dictionary.
    """
    model = None

    # Get model from metadata
    if metadata := run_dict["extra"].get("metadata"):
        model = metadata.get("ls_model_name")

    # Try to detect model+version more precise way if possible
    # .invoke() mode
    if llm_output := run_dict["outputs"].get("llm_output"):
        model = llm_output.get("model_name", model)
    # streaming mode
    elif generation_info := run_dict["outputs"]["generations"][-1][-1][
        "generation_info"
    ]:
        model = generation_info.get("model_name", model)

    return model
