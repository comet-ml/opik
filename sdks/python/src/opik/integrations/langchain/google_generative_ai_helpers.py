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


def map_langchain_usage_fields_to_genai(usage_dict: Dict[str, Any]) -> Dict[str, Any]:
    """Langchain rename genai usage fields
    https://github.com/langchain-ai/langchain-google/blob/0817f7811a2a8bb4df83c9ad8937348fa86576ee/libs/genai/langchain_google_genai/chat_models.py#L721-L725,
    rename them back so genai cost can be computed the same across the
    libraries and frameworks"""

    # In langchain with gemini models, output_tokens already includes thought_tokens
    mapping = {
        "input_tokens": "prompt_token_count",
        "thought_tokens": "thoughts_token_count",
        "output_tokens": "candidates_token_count",
        "cache_read_tokens": "cached_content_token_count",
        "total_tokens": "total_token_count",
    }
    return {mapping.get(key, key): value for key, value in usage_dict.items()}


def _try_get_token_usage(run_dict: Dict[str, Any]) -> Optional[llm_usage.OpikUsage]:
    try:
        # TODO: This is empty in Streaming mode with gemini models
        usage_metadata = run_dict["outputs"]["generations"][-1][-1]["message"][
            "kwargs"
        ]["usage_metadata"]

        opik_usage = llm_usage.OpikUsage.from_google_dict(
            map_langchain_usage_fields_to_genai(usage_metadata)
        )
        return opik_usage
    except Exception:
        LOGGER.warning(
            logging_messages.FAILED_TO_EXTRACT_TOKEN_USAGE_FROM_PRESUMABLY_LANGCHAIN_GOOGLE_LLM_RUN,
            exc_info=True,
        )
        return None


def is_google_generative_ai_run(run: "Run") -> bool:
    try:
        if run.serialized is None:
            return False

        invocation_params = run.extra.get("invocation_params", {})
        provider = invocation_params.get("_type", "").lower()
        is_google_generative_ai = "google-generative-ai" in provider

        return is_google_generative_ai

    except Exception:
        LOGGER.debug(
            "Failed to check if Run instance is from Google Generative AI, returning False.",
            exc_info=True,
        )
        return False


def _get_provider_and_model(
    run_dict: Dict[str, Any],
) -> Tuple[Optional[Union[Literal[LLMProvider.GOOGLE_AI], str]], Optional[str]]:
    """
    Fetches the provider and model information from a given run dictionary.
    """
    provider = None
    model = None

    if invocation_params := run_dict["extra"].get("invocation_params"):
        provider = invocation_params.get("_type").lower()
        if "google-generative-ai" in provider:
            provider = LLMProvider.GOOGLE_AI
        model = invocation_params.get("model")
        if model is not None:
            # Gemini **may** add "models/" prefix to some model versions
            model = model.split("/")[-1]

    return provider, model
