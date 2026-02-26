from __future__ import annotations

import uuid
from dataclasses import asdict

from opik_optimizer_framework.types import Candidate, CandidateConfig
from opik_optimizer_framework.util.hashing import canonical_config_hash


def materialize_candidate(
    config: CandidateConfig,
    step_index: int,
    parent_candidate_ids: list[str] | None = None,
) -> Candidate:
    """Create a Candidate with a generated UUID and computed config hash.

    This is a stub materializer: generates candidate_id, computes config_hash,
    but does not create a mask_id.
    """
    config_dict = asdict(config)
    config_hash = canonical_config_hash(config_dict)

    return Candidate(
        candidate_id=str(uuid.uuid4()),
        config=config,
        config_hash=config_hash,
        step_index=step_index,
        parent_candidate_ids=parent_candidate_ids or [],
    )
