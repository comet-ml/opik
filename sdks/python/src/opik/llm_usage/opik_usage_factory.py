import logging
from typing import Any, Callable, Dict, List, Optional, Union

from opik.types import LLMProvider
from . import opik_usage

LOGGER = logging.getLogger(__name__)


# One provider can have multiple formats of usage dicts, so it can have more than 1 build function
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
    LLMProvider.BEDROCK: [opik_usage.OpikUsage.from_bedrock_dict],
    LLMProvider.MISTRALAI: [opik_usage.OpikUsage.from_mistral_dict],
}


def build_opik_usage(
    provider: Union[str, LLMProvider],
    usage: Dict[str, Any],
) -> opik_usage.OpikUsage:
    build_functions = _PROVIDER_TO_OPIK_USAGE_BUILDERS[provider]

    exc = None
    for build_function in build_functions:
        try:
            result = build_function(usage)
            return result
        except Exception as exc_info:
            exc = exc_info
            pass

    raise ValueError(
        f"Failed to build OpikUsage for provider {provider} and usage {usage}, reason: {exc}"
    )


def build_opik_usage_from_unknown_provider(
    usage: Dict[str, Any],
) -> Optional[opik_usage.OpikUsage]:
    """Best-effort usage parsing for a provider we have no builder for.

    Never raises. This is the last resort behind every provider-specific builder and
    every caller already treats a failure as "no usage", but the generic fallback was
    the one unguarded step here: ``from_unknown_usage_dict`` ends in ``cls(**usage)``,
    so a payload that is not a mapping at all raised straight out of a function whose
    whole contract is best effort - taking down whatever the caller was doing
    alongside the usage.
    """
    for build_functions in _PROVIDER_TO_OPIK_USAGE_BUILDERS.values():
        for build_function in build_functions:
            try:
                opik_usage_ = build_function(usage)
                return opik_usage_
            except Exception:
                pass

    try:
        return opik_usage.OpikUsage.from_unknown_usage_dict(usage)
    except Exception:
        # Not debug: the usage is silently dropped, and nothing else reports it.
        LOGGER.error(
            "Failed to parse token usage of an unknown provider from: %r",
            usage,
            exc_info=True,
        )
        return None


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
