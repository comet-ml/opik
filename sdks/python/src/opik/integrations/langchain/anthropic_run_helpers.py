import logging
from typing import TYPE_CHECKING, Any, Dict, Literal, Optional, Tuple, Union

from opik import llm_usage
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
        if run_dict["outputs"]["llm_output"] is not None:
            usage_dict = run_dict["outputs"]["llm_output"]["usage"]
        else:
            # Handle the streaming mode
            usage_dict = run_dict["outputs"]["generations"][-1][-1]["message"][
                "kwargs"
            ]["usage_metadata"]
            usage_dict = {
                "input_tokens": usage_dict["input_tokens"],
                "output_tokens": usage_dict["output_tokens"],
                "cache_creation_input_tokens": usage_dict["input_token_details"][
                    "cache_creation"
                ],
                "cache_read_input_tokens": usage_dict["input_token_details"][
                    "cache_read"
                ],
            }

        opik_usage = llm_usage.OpikUsage.from_anthropic_dict(usage_dict)
        return opik_usage
    except Exception:
        LOGGER.warning(
            "Failed to extract token usage from presumably Anthropic LLM langchain run.",
            exc_info=True,
        )
        return None


def is_anthropic_run(run: "Run") -> bool:
    try:
        if run.serialized is None:
            return False

        serialized_kwargs = run.serialized.get("kwargs", {})
        has_anthropic_key = "anthropic_api_key" in serialized_kwargs

        return has_anthropic_key

    except Exception:
        LOGGER.debug(
            "Failed to check if Run instance is from Anthropic LLM, returning False.",
            exc_info=True,
        )
        return False


def _get_provider_and_model(
    run_dict: Dict[str, Any],
) -> Tuple[Optional[Union[Literal[LLMProvider.ANTHROPIC], str]], Optional[str]]:
    """
    Fetches the provider and model information from a given run dictionary.
    """
    provider = LLMProvider.ANTHROPIC
    if run_dict["outputs"]["llm_output"] is not None:
        model = run_dict["outputs"]["llm_output"]["model_name"]
    else:
        # Handle the streaming mode
        model = run_dict["outputs"]["generations"][-1][-1]["message"]["kwargs"][
            "response_metadata"
        ]["model_name"]

    return provider, model
