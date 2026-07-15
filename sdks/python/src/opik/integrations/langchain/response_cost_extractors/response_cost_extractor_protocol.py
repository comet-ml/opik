from typing import Any, Dict, Optional, Protocol


class ResponseCostExtractorProtocol(Protocol):
    def try_get_response_cost(
        self, response_metadata: Dict[str, Any]
    ) -> Optional[float]:
        """
        Returns the provider-reported cost (in USD) found in an LLM run's
        ``response_metadata``, or None if this extractor doesn't recognize it.

        Implementations should look only for their own fingerprint (e.g. a
        specific proxy response header) so multiple extractors can coexist.
        """
        ...
