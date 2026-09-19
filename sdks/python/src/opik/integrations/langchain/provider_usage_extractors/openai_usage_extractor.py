import logging
from typing import TYPE_CHECKING, Any, Dict, Optional
from urllib.parse import urlsplit
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
        invocation_params = (run_dict.get("extra") or {}).get("invocation_params")
        if isinstance(invocation_params, dict) and (
            base_url := invocation_params.get("base_url")
        ):
            host = _try_get_base_url_host(base_url)
            if host is not None and host != "api.openai.com":
                provider = host

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
    metadata = (run_dict.get("extra") or {}).get("metadata")
    if isinstance(metadata, dict):
        model = metadata.get("ls_model_name")

    # Try to detect model+version more precise way if possible
    # .invoke() mode
    outputs = run_dict.get("outputs") or {}
    if llm_output := outputs.get("llm_output"):
        model = llm_output.get("model_name", model)
    # streaming mode
    elif generations := outputs.get("generations"):
        last_generation = generations[-1][-1] if generations[-1] else None
        if last_generation is not None:
            generation_info = last_generation.get("generation_info")
            if generation_info:
                model = generation_info.get("model_name", model)

    return model


def _try_get_base_url_host(base_url: Any) -> Optional[str]:
    """
    The host `base_url.host` reports, whichever shape the serialised run carries.

    LangChain passes this value either as a URL object or as the plain string the
    user configured. Reading `.host` off a string raises, and that exception escapes
    `get_llm_usage_info`, so the orchestrator discards usage that was already
    extracted and the span is logged without tokens, model or cost. Parsing the
    string reports what `httpx.URL` reports for the same text, the empty string for
    a text carrying no host included, so both shapes name the same provider. None is
    returned only for a value that is not URL-shaped at all, which leaves the
    provider at its default.
    """
    host = getattr(base_url, "host", None)
    if isinstance(host, str):
        return host

    if not isinstance(base_url, str):
        return None

    try:
        parsed_host = urlsplit(base_url).hostname
    except ValueError:
        # Not a parseable URL, an unbalanced IPv6 literal for instance.
        parsed_host = None

    return "" if parsed_host is None else parsed_host
