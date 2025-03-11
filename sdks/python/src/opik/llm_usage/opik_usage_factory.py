import logging
from . import opik_usage
from opik import dict_utils
from typing import Dict, Any, Callable, Optional, Union
from opik.types import LLMProvider

_PROVIDER_TO_OPIK_USAGE_BUILDER: Dict[
    LLMProvider, Callable[[Dict[str, Any]], opik_usage.OpikUsage]
] = {
    LLMProvider.GOOGLE_VERTEXAI: opik_usage.OpikUsage.from_google_dict,
    LLMProvider.GOOGLE_AI: opik_usage.OpikUsage.from_google_dict,
    LLMProvider.OPENAI: opik_usage.OpikUsage.from_openai_completions_dict,
    LLMProvider.ANTHROPIC: opik_usage.OpikUsage.from_anthropic_dict,
}


def build_opik_usage(
    provider: LLMProvider,
    usage: Dict[str, Any],
) -> opik_usage.OpikUsage:
    build_function = _PROVIDER_TO_OPIK_USAGE_BUILDER[provider]

    result = build_function(usage)

    return result


def try_build_backend_compatible_usage_from_unknown_provider(
    usage: Dict[str, Any],
) -> Union[Dict[str, int], opik_usage.OpikUsage]:
    for build_function in _PROVIDER_TO_OPIK_USAGE_BUILDER.values():
        try:
            opik_usage = build_function(usage)
            return opik_usage
        except Exception:
            pass

    backend_compatible_dictionary = dict_utils.flatten_dict(
        usage, parent_key="original_usage", delim="."
    )
    backend_compatible_dictionary = dict_utils.keep_only_values_of_type(
        backend_compatible_dictionary, value_type=int
    )
    return backend_compatible_dictionary


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
