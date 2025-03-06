from . import opik_usage
from typing import Dict, Any, Callable
from opik.types import LLMProvider

_PROVIDER_TO_OPIK_USAGE_BUILDER: Dict[
    LLMProvider, Callable[[Dict[str, Any]], opik_usage.OpikUsage]
] = {
    LLMProvider.GOOGLE_VERTEXAI: opik_usage.OpikUsage.from_google_dict,
    LLMProvider.GOOGLE_AI: opik_usage.OpikUsage.from_google_dict,
    LLMProvider.OPENAI: opik_usage.OpikUsage.from_openai_completions_dict,
}


def build_opik_usage(
    provider: LLMProvider, usage: Dict[str, Any]
) -> opik_usage.OpikUsage:
    build_function = _PROVIDER_TO_OPIK_USAGE_BUILDER[provider]

    result = build_function(usage)

    return result
