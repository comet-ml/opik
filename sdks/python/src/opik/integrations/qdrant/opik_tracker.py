from __future__ import annotations

from typing import Any, Optional

from opik.integrations._retrieval_tracker import (
    RetrievalTrackingConfig,
    as_tuple,
    patch_retrieval_client,
)


def track_qdrant(qdrant_client: Any, project_name: Optional[str] = None) -> Any:
    """Adds Opik tracking wrappers to a Qdrant client.

    The integration is dependency-light and patches known retrieval methods if present.
    """
    config = RetrievalTrackingConfig(
        provider="qdrant",
        operation_paths=as_tuple(
            [
                "query",
                "query_points",
                "search",
                "search_batch",
                "recommend",
                "discover",
                "scroll",
            ]
        ),
        project_name=project_name,
    )
    return patch_retrieval_client(qdrant_client, config)
