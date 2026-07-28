import logging
from typing import Any, Dict, Optional

LOGGER = logging.getLogger(__name__)


class LiteLLMResponseCostExtractor:
    """
    Extracts the cost reported by a LiteLLM proxy.

    The proxy returns the request cost in the ``x-litellm-response-cost``
    response header. LangChain only surfaces response headers when the chat
    model is created with ``include_response_headers=True``, in which case they
    land under ``response_metadata["headers"]``. We also check the top level of
    ``response_metadata`` to stay robust across LangChain versions/setups that
    flatten the header.
    """

    RESPONSE_COST_KEY = "x-litellm-response-cost"

    def try_get_response_cost(
        self, response_metadata: Dict[str, Any]
    ) -> Optional[float]:
        raw_cost = response_metadata.get(self.RESPONSE_COST_KEY)

        if raw_cost is None:
            headers = response_metadata.get("headers")
            if isinstance(headers, dict):
                raw_cost = headers.get(self.RESPONSE_COST_KEY)

        if raw_cost is None:
            return None

        try:
            return float(raw_cost)
        except (TypeError, ValueError):
            LOGGER.debug(
                "Failed to parse LiteLLM response cost from value: %r", raw_cost
            )
            return None
