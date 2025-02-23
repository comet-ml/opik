import logging
from typing import Any, Dict, Final, Optional, TYPE_CHECKING, Tuple

from opik import logging_messages
from opik.types import LLMProvider, LLMUsageInfo, UsageDictGoogle
from opik.validation import usage as usage_validator

if TYPE_CHECKING:
    from langchain_core.tracers.schemas import Run

LOGGER = logging.getLogger(__name__)

PROVIDER_NAME: Final[LLMProvider] = "google_vertexai"


def get_llm_usage_info(run_dict: Optional[Dict[str, Any]] = None) -> LLMUsageInfo:
    if run_dict is None:
        return LLMUsageInfo()

    usage_dict = _try_get_token_usage(run_dict)
    provider, model = _get_provider_and_model(run_dict)

    return LLMUsageInfo(provider=provider, model=model, usage=usage_dict)


def _try_get_token_usage(run_dict: Dict[str, Any]) -> Optional[UsageDictGoogle]:
    try:
        provider, _ = _get_provider_and_model(run_dict)

        usage_metadata = run_dict["outputs"]["generations"][-1][-1]["generation_info"][
            "usage_metadata"
        ]

        token_usage = UsageDictGoogle(
            completion_tokens=usage_metadata["candidates_token_count"],
            prompt_tokens=usage_metadata["prompt_token_count"],
            total_tokens=usage_metadata["total_token_count"],
            **usage_metadata,
        )  # type: ignore

        if (
            usage_validator.UsageValidator(
                usage=token_usage,
                provider=provider,
            )
            .validate()
            .ok()
        ):
            return token_usage

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
) -> Tuple[Optional[str], Optional[str]]:
    """
    Fetches the provider and model information from a given run dictionary.
    """
    provider = None
    model = None

    if invocation_params := run_dict["extra"].get("invocation_params"):
        provider = invocation_params.get("_type")
        if provider == "vertexai":
            provider = PROVIDER_NAME
        model = invocation_params.get("model_name")

    return provider, model
