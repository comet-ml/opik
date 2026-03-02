from __future__ import annotations

import uuid

from opik_optimizer_framework.types import Candidate, CandidateConfig


def materialize_candidate(
    config: CandidateConfig,
    step_index: int,
    parent_candidate_ids: list[str] | None = None,
    candidate_id: str | None = None,
) -> Candidate:
    """Create a Candidate with a generated UUID.

    This is a stub materializer: generates candidate_id but does not create a mask_id.
    """
    return Candidate(
        candidate_id=candidate_id or str(uuid.uuid4()),
        config=config,
        step_index=step_index,
        parent_candidate_ids=parent_candidate_ids or [],
    )
