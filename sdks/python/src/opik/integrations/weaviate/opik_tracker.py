from __future__ import annotations

from typing import Any, Optional

from opik.integrations._retrieval_tracker import (
    RetrievalTrackingConfig,
    as_tuple,
    patch_retrieval_client,
)


def track_weaviate(weaviate_client_or_collection: Any, project_name: Optional[str] = None) -> Any:
    """Adds Opik tracking wrappers to a Weaviate client or collection/query object."""
    config = RetrievalTrackingConfig(
        provider="weaviate",
        operation_paths=as_tuple(
            [
                "query.get",
                "query.raw",
                "query.hybrid",
                "query.near_text",
                "query.near_vector",
                "query.fetch_objects",
                "query.bm25",
            ]
        ),
        project_name=project_name,
    )
    return patch_retrieval_client(weaviate_client_or_collection, config)
