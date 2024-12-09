import dataclasses
import logging
from typing import Any, Dict, Optional, TYPE_CHECKING, Tuple, cast

from opik import logging_messages
from opik.types import UsageDict
from opik.validation import usage as usage_validator

if TYPE_CHECKING:
    from langchain_core.tracers.schemas import Run


LOGGER = logging.getLogger(__name__)


@dataclasses.dataclass
class LLMUsageInfo:
    provider: Optional[str] = None
    model: Optional[str] = None
    token_usage: Optional[UsageDict] = None


def get_llm_usage_info(run_dict: Optional[Dict[str, Any]] = None) -> LLMUsageInfo:
    if run_dict is None:
        return LLMUsageInfo()

    usage_dict = _try_get_token_usage(run_dict)
    provider, model = _get_provider_and_model(run_dict)

    return LLMUsageInfo(provider=provider, model=model, token_usage=usage_dict)


def _try_get_token_usage(run_dict: Dict[str, Any]) -> Optional[UsageDict]:
    try:
        token_usage = run_dict["outputs"]["llm_output"]["token_usage"]
        if usage_validator.UsageValidator(token_usage).validate().ok():
            return cast(UsageDict, token_usage)

        return None
    except Exception:
        LOGGER.warning(
            logging_messages.FAILED_TO_EXTRACT_TOKEN_USAGE_FROM_PRESUMABLY_LANGCHAIN_OPENAI_LLM_RUN,
            exc_info=True,
        )
        return None


def is_openai_run(run: "Run") -> bool:
    try:
        if run.serialized is None:
            return False

        serialized_kwargs = run.serialized.get("kwargs", {})
        has_openai_key = "openai_api_key" in serialized_kwargs

        return has_openai_key

    except Exception:
        LOGGER.debug(
            "Failed to check if Run instance is from OpenAI LLM, returning False.",
            exc_info=True,
        )
        return False


def _get_provider_and_model(
    run_dict: Dict[str, Any],
) -> Tuple[Optional[str], Optional[str]]:
    """
    Fetches the provider and model information from a given run dictionary.

    By default, the provider is assumed to be OpenAI (will be available in extra/metadata field).
    If LLM output is available, the model version is included.
    If the Client is available, the BaseURL is also checked.
    """
    provider = None
    model = None

    if metadata := run_dict["extra"].get("metadata"):
        provider = metadata.get("ls_provider")
        model = metadata.get("ls_model_name")

    if llm_output := run_dict["outputs"].get("llm_output"):
        model = llm_output.get("model_name", model)

    if base_url := run_dict["extra"].get("invocation_params", {}).get("base_url"):
        if base_url.host != "api.openai.com":
            provider = base_url.host

    return provider, model
