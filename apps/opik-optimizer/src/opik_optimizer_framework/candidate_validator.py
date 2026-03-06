from __future__ import annotations

import logging

from opik_optimizer_framework.types import CandidateConfig

logger = logging.getLogger(__name__)


def validate_candidate(
    config: CandidateConfig,
    optimizable_keys: list[str],
) -> tuple[bool, str | None]:
    """Validate a candidate configuration.

    Returns (True, None) if valid, or (False, reason) if rejected.
    Checks that every optimizable key is present with a non-empty string value.
    """
    for key in optimizable_keys:
        value = config.get(key)
        if not isinstance(value, str) or not value:
            return False, f"missing_or_empty:{key}"

    return True, None
