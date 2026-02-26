from __future__ import annotations

import logging
from dataclasses import asdict

from opik_optimizer_framework.types import CandidateConfig, OptimizationState
from opik_optimizer_framework.util.hashing import canonical_config_hash

logger = logging.getLogger(__name__)


def validate_candidate(
    config: CandidateConfig,
    state: OptimizationState,
) -> tuple[bool, str | None]:
    """Validate a candidate configuration.

    Returns (True, None) if valid, or (False, reason) if rejected.
    Checks:
    1. Shape validation: non-empty messages, each has role+content
    2. Dedup: config hash not already in state.seen_hashes
    """
    if not config.prompt_messages:
        return False, "empty_messages"

    for i, msg in enumerate(config.prompt_messages):
        if "role" not in msg or "content" not in msg:
            return False, f"message_{i}_missing_role_or_content"
        if not msg["role"] or not msg["content"]:
            return False, f"message_{i}_empty_role_or_content"

    config_dict = asdict(config)
    config_h = canonical_config_hash(config_dict)
    if config_h in state.seen_hashes:
        return False, "duplicate_config_hash"

    return True, None
