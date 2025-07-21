import logging
from typing import TYPE_CHECKING, Any, Dict, Optional, Tuple, Literal

from opik import _logging as opik_logging
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

    opik_usage = _try_get_token_usage(run_dict)
    provider, model = _get_provider_and_model(run_dict)

    return llm_usage.LLMUsageInfo(provider=provider, model=model, usage=opik_usage)


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
        elif token_usage_dict := run_dict["outputs"]["generations"][-1][-1]["message"][
            "kwargs"
        ].get("usage_metadata"):
            generic_formatted_dict = {
                "completion_tokens": token_usage_dict["output_tokens"],
                "prompt_tokens": token_usage_dict["input_tokens"],
                "total_tokens": token_usage_dict["total_tokens"],
            }
            opik_usage = llm_usage.OpikUsage.from_groq_completions_dict(
                generic_formatted_dict
            )
            return opik_usage
        else:
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


def is_groq_run(run: "Run") -> bool:
    try:
        if run.serialized is None:
            return False

        serialized_kwargs = run.serialized.get("kwargs", {})
        has_groq_key = "groq_api_key" in serialized_kwargs

        return has_groq_key

    except Exception:
        LOGGER.debug(
            "Failed to check if Run instance is from Groq LLM, returning False.",
            exc_info=True,
        )
        return False


def _get_provider_and_model(
    run_dict: Dict[str, Any],
) -> Tuple[Literal["groq"], Optional[str]]:
    """
    Fetches the provider and model information from a given run dictionary.
    """
    model: Optional[str] = None
    provider = LLMProvider.GROQ
    model = run_dict["extra"].get("metadata", {}).get("ls_model_name")
    if model is not None:
        model = model.split("/")[-1]

    return provider, model  # type: ignore
