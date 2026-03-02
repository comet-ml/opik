from __future__ import annotations

import logging

from opik_optimizer_framework.types import CandidateConfig, OptimizationState

logger = logging.getLogger(__name__)


def validate_candidate(
    config: CandidateConfig,
    state: OptimizationState,
) -> tuple[bool, str | None]:
    """Validate a candidate configuration.

    Returns (True, None) if valid, or (False, reason) if rejected.
    Checks shape validation: non-empty messages, each has role+content.
    """
    if not config.prompt_messages:
        return False, "empty_messages"

    for i, msg in enumerate(config.prompt_messages):
        if "role" not in msg or "content" not in msg:
            return False, f"message_{i}_missing_role_or_content"
        if not msg["role"] or not msg["content"]:
            return False, f"message_{i}_empty_role_or_content"

    return True, None
