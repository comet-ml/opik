from __future__ import annotations

from typing import Any, Optional

from opik.integrations._retrieval_tracker import (
    RetrievalTrackingConfig,
    as_tuple,
    patch_retrieval_client,
)


def track_chroma(chroma_client_or_collection: Any, project_name: Optional[str] = None) -> Any:
    """Adds Opik tracking wrappers to a Chroma client or collection object."""
    config = RetrievalTrackingConfig(
        provider="chroma",
        operation_paths=as_tuple(
            [
                "query",
                "get",
            ]
        ),
        project_name=project_name,
    )
    return patch_retrieval_client(chroma_client_or_collection, config)
