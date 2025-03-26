import logging
from . import opik_usage
from typing import Dict, Any, Callable, Optional, Union
from opik.types import LLMProvider

_PROVIDER_TO_OPIK_USAGE_BUILDER: Dict[
    Union[str, LLMProvider], Callable[[Dict[str, Any]], opik_usage.OpikUsage]
] = {
    LLMProvider.OPENAI: opik_usage.OpikUsage.from_openai_completions_dict,
    LLMProvider.GOOGLE_VERTEXAI: opik_usage.OpikUsage.from_google_dict,
    LLMProvider.GOOGLE_AI: opik_usage.OpikUsage.from_google_dict,
    LLMProvider.ANTHROPIC: opik_usage.OpikUsage.from_anthropic_dict,
    "_bedrock": opik_usage.OpikUsage.from_bedrock_dict,
    "_openai_agent": opik_usage.OpikUsage.from_openai_agent_dict,
}


def build_opik_usage(
    provider: Union[str, LLMProvider],
    usage: Dict[str, Any],
) -> opik_usage.OpikUsage:
    build_function = _PROVIDER_TO_OPIK_USAGE_BUILDER[provider]

    result = build_function(usage)

    return result


def build_opik_usage_from_unknown_provider(
    usage: Dict[str, Any],
) -> opik_usage.OpikUsage:
    for build_function in _PROVIDER_TO_OPIK_USAGE_BUILDER.values():
        try:
            opik_usage_ = build_function(usage)
            return opik_usage_
        except Exception:
            pass

    return opik_usage.OpikUsage.from_unknown_usage_dict(usage)


def try_build_opik_usage_or_log_error(
    provider: Union[str, LLMProvider],
    usage: Dict[str, Any],
    logger: logging.Logger,
    error_message: str,
) -> Optional[opik_usage.OpikUsage]:
    try:
        return build_opik_usage(provider=provider, usage=usage)
    except Exception:
        logger.error(error_message, exc_info=True)
        return None
