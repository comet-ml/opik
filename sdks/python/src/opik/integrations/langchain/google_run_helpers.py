import logging
from typing import Any, Dict, Optional, TYPE_CHECKING, Tuple, cast

from opik import logging_messages
from opik.types import LLMUsageInfo, UsageDict
from opik.validation import usage as usage_validator

if TYPE_CHECKING:
    from langchain_core.tracers.schemas import Run

LOGGER = logging.getLogger(__name__)


def get_llm_usage_info(run_dict: Optional[Dict[str, Any]] = None) -> LLMUsageInfo:
    if run_dict is None:
        return LLMUsageInfo()

    usage_dict = _try_get_token_usage(run_dict)
    provider, model = _get_provider_and_model(run_dict)

    return LLMUsageInfo(provider=provider, model=model, usage=usage_dict)


def _try_get_token_usage(run_dict: Dict[str, Any]) -> Optional[UsageDict]:
    try:
        usage_metadata = run_dict["outputs"]["generations"][-1][-1]["generation_info"][
            "usage_metadata"
        ]

        token_usage = UsageDict(
            completion_tokens=usage_metadata["candidates_token_count"],
            prompt_tokens=usage_metadata["prompt_token_count"],
            total_tokens=usage_metadata["total_token_count"],
        )
        token_usage.update(usage_metadata)

        if usage_validator.UsageValidator(token_usage).validate().ok():
            return cast(UsageDict, token_usage)

        return None
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

        provider = run.metadata.get("ls_provider", "")
        is_google = "google" in provider.lower()

        return is_google

    except Exception:
        LOGGER.debug(
            "Failed to check if Run instance is from Google LLM, returning False.",
            exc_info=True,
        )
        return False


def _get_provider_and_model(
    run_dict: Dict[str, Any],
) -> Tuple[Optional[str], Optional[str]]:
    """
    Fetches the provider and model information from a given run dictionary.
    """
    provider = None
    model = None

    if metadata := run_dict["extra"].get("metadata"):
        provider = metadata.get("ls_provider")
        model = metadata.get("ls_model_name")

    return provider, model
