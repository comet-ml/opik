from __future__ import annotations

import logging

from opik_optimizer_framework.tasks import MESSAGE_KEYS
from opik_optimizer_framework.types import CandidateConfig, OptimizationState

logger = logging.getLogger(__name__)


def validate_candidate(
    config: CandidateConfig,
    state: OptimizationState,
) -> tuple[bool, str | None]:
    """Validate a candidate configuration.

    Returns (True, None) if valid, or (False, reason) if rejected.
    Accepts either prompt_messages (list of role/content dicts) or flat
    message keys (system_prompt, user_message, etc.).
    """
    has_flat_keys = any(config.get(key) for key, _ in MESSAGE_KEYS)
    has_prompt_messages = bool(config.get("prompt_messages"))

    if not has_flat_keys and not has_prompt_messages:
        return False, "empty_messages"

    if has_prompt_messages:
        for i, msg in enumerate(config["prompt_messages"]):
            if "role" not in msg or "content" not in msg:
                return False, f"message_{i}_missing_role_or_content"
            if not msg["role"] or not msg["content"]:
                return False, f"message_{i}_empty_role_or_content"

    return True, None
