import logging
from typing import Any, Dict, Optional, Tuple

from opik import llm_usage, logging_messages
from opik.integrations.langchain import langchain_run_helpers
from opik.types import LLMProvider


LOGGER = logging.getLogger(__name__)


def get_llm_usage_info(
    run_dict: Optional[Dict[str, Any]] = None,
) -> llm_usage.LLMUsageInfo:
    if run_dict is None:
        return llm_usage.LLMUsageInfo()

    usage_dict = _try_get_token_usage(run_dict)
    provider, model = _get_provider_and_model(run_dict)

    return llm_usage.LLMUsageInfo(provider=provider, model=model, usage=usage_dict)


def _try_get_token_usage(run_dict: Dict[str, Any]) -> Optional[llm_usage.OpikUsage]:
    try:
        usage_metadata = langchain_run_helpers.try_get_token_usage(
            run_dict
        ).map_to_google_gemini_usage()

        return llm_usage.OpikUsage.from_google_dict(usage_metadata)
    except Exception:
        LOGGER.warning(
            logging_messages.FAILED_TO_EXTRACT_TOKEN_USAGE_FROM_PRESUMABLY_LANGCHAIN_GOOGLE_LLM_RUN,
            exc_info=True,
        )
        return None


def is_google_generative_ai_run(run_dict: Dict[str, Any]) -> bool:
    try:
        if run_dict.get("serialized") is None:
            return False

        provider, _ = _get_provider_and_model(run_dict)
        return provider is not None and provider == LLMProvider.GOOGLE_AI

    except Exception as ex:
        LOGGER.debug(
            "Failed to check if Run instance is from Google Generative AI, returning False. Reason: %s",
            ex,
            exc_info=True,
        )
        return False


def _get_provider_and_model(
    run_dict: Dict[str, Any],
) -> Tuple[Optional[LLMProvider], Optional[str]]:
    """
    Fetches the provider and model information from a given run dictionary.
    """
    provider = None
    model = None

    # try metadata first
    if (ls_metadata := langchain_run_helpers.try_get_ls_metadata(run_dict)) is not None:
        if "google_genai" == ls_metadata.provider:
            provider = LLMProvider.GOOGLE_AI

        model = ls_metadata.model

    elif (invocation_params := run_dict["extra"].get("invocation_params")) is not None:
        if _is_google_gen_ai_type(invocation_params.get("_type").lower()):
            provider = LLMProvider.GOOGLE_AI
        model = invocation_params.get("model")

    if model is not None:
        # Gemini **may** add "models/" prefix to some model versions
        model = model.split("/")[-1]

    return provider, model


def _is_google_gen_ai_type(provider: str) -> bool:
    return "google-generative-ai" == provider or "google_gemini" == provider
