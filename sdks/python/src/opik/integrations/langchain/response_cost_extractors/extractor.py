import logging
from typing import Any, Dict, List, Optional

from . import litellm_response_cost_extractor, response_cost_extractor_protocol

LOGGER = logging.getLogger(__name__)

_REGISTERED_RESPONSE_COST_EXTRACTORS: List[
    response_cost_extractor_protocol.ResponseCostExtractorProtocol
] = [
    litellm_response_cost_extractor.LiteLLMResponseCostExtractor(),
]


def try_extract_response_cost(run_dict: Dict[str, Any]) -> Optional[float]:
    """
    Attempts to extract a provider-reported cost (in USD) from an LLM run.

    Proxies such as LiteLLM return the request cost in a response header that
    LangChain surfaces under the message ``response_metadata``. This is a real,
    provider-reported cost, so it takes priority over the cost Opik estimates
    from token usage.

    New proxies/integrations can be supported by adding an extractor to
    ``_REGISTERED_RESPONSE_COST_EXTRACTORS``.
    """
    response_metadata = _try_get_response_metadata(run_dict)
    if not response_metadata:
        return None

    for extractor in _REGISTERED_RESPONSE_COST_EXTRACTORS:
        try:
            cost = extractor.try_get_response_cost(response_metadata)
        except Exception:
            LOGGER.debug(
                "Failed to extract response cost with %s.",
                type(extractor).__name__,
                exc_info=True,
            )
            continue

        if cost is not None:
            return cost

    return None


def _try_get_response_metadata(run_dict: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    try:
        message = run_dict["outputs"]["generations"][-1][-1]["message"]
        return message["kwargs"].get("response_metadata")
    except (KeyError, IndexError, TypeError):
        return None
