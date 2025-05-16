import logging
from typing import Any, Callable, Dict, List, Optional, Union

from opik.types import LLMProvider
from . import opik_usage


# One provider can have multiple formats of usage dicts, so it could be many build functions
# if provider's name specified as string and not as LLMProvider enum value -
# it means that we do not support cost tracking for this provider (but support usage info)
_PROVIDER_TO_OPIK_USAGE_BUILDERS: Dict[
    Union[str, LLMProvider],
    List[Callable[[Dict[str, Any]], opik_usage.OpikUsage]],
] = {
    LLMProvider.OPENAI: [
        opik_usage.OpikUsage.from_openai_completions_dict,
        opik_usage.OpikUsage.from_openai_responses_dict,
    ],
    LLMProvider.GOOGLE_VERTEXAI: [opik_usage.OpikUsage.from_google_dict],
    LLMProvider.GOOGLE_AI: [opik_usage.OpikUsage.from_google_dict],
    LLMProvider.ANTHROPIC: [opik_usage.OpikUsage.from_anthropic_dict],
    "_bedrock": [opik_usage.OpikUsage.from_bedrock_dict],
}


def build_opik_usage(
    provider: Union[str, LLMProvider],
    usage: Dict[str, Any],
) -> opik_usage.OpikUsage:
    build_functions = _PROVIDER_TO_OPIK_USAGE_BUILDERS[provider]

    for build_function in build_functions:
        try:
            result = build_function(usage)
            return result
        except Exception:
            pass

    raise ValueError(
        f"Failed to build OpikUsage for provider {provider} and usage {usage}"
    )


def build_opik_usage_from_unknown_provider(
    usage: Dict[str, Any],
) -> opik_usage.OpikUsage:
    for build_functions in _PROVIDER_TO_OPIK_USAGE_BUILDERS.values():
        for build_function in build_functions:
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
