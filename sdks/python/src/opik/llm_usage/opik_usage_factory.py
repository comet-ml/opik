import logging
from . import opik_usage
from typing import Dict, Any, Callable, Optional
from opik.types import LLMProvider

_PROVIDER_TO_OPIK_USAGE_BUILDER: Dict[
    LLMProvider, Callable[[Dict[str, Any]], opik_usage.OpikUsage]
] = {
    LLMProvider.GOOGLE_VERTEXAI: opik_usage.OpikUsage.from_google_dict,
    LLMProvider.GOOGLE_AI: opik_usage.OpikUsage.from_google_dict,
    LLMProvider.OPENAI: opik_usage.OpikUsage.from_openai_completions_dict,
}


def build_opik_usage(
    provider: LLMProvider,
    usage: Dict[str, Any],
) -> opik_usage.OpikUsage:
    build_function = _PROVIDER_TO_OPIK_USAGE_BUILDER[provider]

    result = build_function(usage)

    return result


def try_build_opik_usage_or_log_error(
    provider: LLMProvider,
    usage: Dict[str, Any],
    logger: logging.Logger,
    error_message: str,
) -> Optional[opik_usage.OpikUsage]:
    try:
        return build_opik_usage(provider=provider, usage=usage)
    except Exception:
        logger.error(error_message, exc_info=True)
        return None
