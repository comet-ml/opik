import logging
from typing import TYPE_CHECKING, Any, Dict, Literal, Optional, Tuple, Union

from opik import llm_usage, logging_messages
from opik.types import LLMProvider

if TYPE_CHECKING:
    from langchain_core.tracers.schemas import Run

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
        # TODO: This is empty in Streaming mode with gemini models
        usage_metadata = run_dict["outputs"]["generations"][-1][-1]["generation_info"][
            "usage_metadata"
        ]

        opik_usage = llm_usage.OpikUsage.from_google_dict(usage_metadata)
        return opik_usage
    except Exception:
        LOGGER.warning(
            logging_messages.FAILED_TO_EXTRACT_TOKEN_USAGE_FROM_PRESUMABLY_LANGCHAIN_GOOGLE_LLM_RUN,
            exc_info=True,
        )
        return None


def is_google_run(run: "Run") -> bool:
    try:
        if run.serialized is None:
            return False

        invocation_params = run.extra.get("invocation_params", {})
        provider = invocation_params.get("_type", "").lower()
        is_google = "vertexai" in provider.lower()

        return is_google

    except Exception:
        LOGGER.debug(
            "Failed to check if Run instance is from Google LLM, returning False.",
            exc_info=True,
        )
        return False


def _get_provider_and_model(
    run_dict: Dict[str, Any],
) -> Tuple[Optional[Union[Literal[LLMProvider.GOOGLE_VERTEXAI], str]], Optional[str]]:
    """
    Fetches the provider and model information from a given run dictionary.
    """
    provider = None
    model = None

    if invocation_params := run_dict["extra"].get("invocation_params"):
        provider = invocation_params.get("_type")
        if provider == "vertexai":
            provider = LLMProvider.GOOGLE_VERTEXAI
        model = invocation_params.get("model_name")
        if model is not None:
            # Gemini **may** add "models/" prefix to some model versions
            model = model.split("/")[-1]

    return provider, model
