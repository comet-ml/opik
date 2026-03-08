from __future__ import annotations

from typing import Any, Optional

from opik.integrations._retrieval_tracker import (
    RetrievalTrackingConfig,
    as_tuple,
    patch_retrieval_client,
)


def track_pinecone(pinecone_index_or_client: Any, project_name: Optional[str] = None) -> Any:
    """Adds Opik tracking wrappers to a Pinecone index or client object."""
    config = RetrievalTrackingConfig(
        provider="pinecone",
        operation_paths=as_tuple(
            [
                "query",
                "search",
                "search_records",
                "fetch",
            ]
        ),
        project_name=project_name,
    )
    return patch_retrieval_client(pinecone_index_or_client, config)
