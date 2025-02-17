import logging
from typing import Any, Dict, Optional, TYPE_CHECKING, Tuple, cast

from opik import _logging as opik_logging, logging_messages
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
    """
    Attempts to extract and return the token usage from the given run dictionary.

    Depending on the execution type (invoke, streaming mode, async etc.), or even the model name itself,
    token usage info might be in different places, different formats or completely missing.
    """
    try:
        provider, _ = _get_provider_and_model(run_dict)

        if run_dict["outputs"]["llm_output"] is not None:
            token_usage = run_dict["outputs"]["llm_output"]["token_usage"]

        # streaming mode handling
        # token usage data MAY be available at the end of streaming
        # in async mode may not provide token usage info
        elif token_usage_full := run_dict["outputs"]["generations"][-1][-1]["message"][
            "kwargs"
        ].get("usage_metadata"):
            token_usage = UsageDict(
                completion_tokens=token_usage_full["output_tokens"],
                prompt_tokens=token_usage_full["input_tokens"],
                total_tokens=token_usage_full["total_tokens"],
            )
            token_usage.update(token_usage_full)
        else:
            opik_logging.log_once_at_level(
                logging_level=logging.WARNING,
                message=logging_messages.WARNING_TOKEN_USAGE_DATA_IS_NOT_AVAILABLE,
                logger=LOGGER,
            )
            return None

        if (
            usage_validator.UsageValidator(usage=token_usage, provider=provider)
            .validate()
            .ok()
        ):
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

    # here will be always provider and model
    # but model name may not be full (model+version)
    if metadata := run_dict["extra"].get("metadata"):
        provider = metadata.get("ls_provider")
        model = metadata.get("ls_model_name")

    # try to detect model+version more precise way if possible
    # .invoke() mode
    if llm_output := run_dict["outputs"].get("llm_output"):
        model = llm_output.get("model_name", model)
    # streaming mode
    elif generation_info := run_dict["outputs"]["generations"][-1][-1][
        "generation_info"
    ]:
        model = generation_info.get("model_name", model)

    # provider: check base url
    if base_url := run_dict["extra"].get("invocation_params", {}).get("base_url"):
        if base_url.host != "api.openai.com":
            provider = base_url.host

    return provider, model
