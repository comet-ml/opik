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
        # try raw VertexAI usage from generation_info
        if (
            usage_metadata := run_dict["outputs"]["generations"][-1][-1][
                "generation_info"
            ].get("usage_metadata")
        ) is not None:
            # inside LangGraph studio we have an empty usage_metadata dictionary here and
            # should fallback to streaming token usage
            if len(usage_metadata) >= 3:
                return llm_usage.OpikUsage.from_google_dict(usage_metadata)

        usage = langchain_run_helpers.try_get_streaming_token_usage(run_dict)
        if usage is not None:
            return llm_usage.OpikUsage.from_google_dict(
                usage.map_to_google_gemini_usage()
            )

        raise Exception("No token usage found in the run dictionary.")
    except Exception:
        LOGGER.warning(
            logging_messages.FAILED_TO_EXTRACT_TOKEN_USAGE_FROM_PRESUMABLY_LANGCHAIN_GOOGLE_LLM_RUN,
            exc_info=True,
        )
        return None


def is_vertexai_run(run_dict: Dict[str, Any]) -> bool:
    try:
        if run_dict.get("serialized") is None:
            return False

        provider, _ = _get_provider_and_model(run_dict)
        return provider is not None and provider == LLMProvider.GOOGLE_VERTEXAI

    except Exception:
        LOGGER.debug(
            "Failed to check if Run instance is from VertexAI, returning False.",
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
        if "google_vertexai" == ls_metadata.provider:
            provider = LLMProvider.GOOGLE_VERTEXAI

        model = ls_metadata.model

    elif (invocation_params := run_dict["extra"].get("invocation_params")) is not None:
        if "vertexai" == invocation_params.get("_type"):
            provider = LLMProvider.GOOGLE_VERTEXAI
        model = invocation_params.get("model_name")

    if model is not None:
        # Gemini **may** add "models/" prefix to some model versions
        model = model.split("/")[-1]

    return provider, model
