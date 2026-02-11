from __future__ import annotations

from typing import Any
from collections.abc import Iterable
import copy

ALLOWED_PROMPT_ROLES = {"system", "user", "assistant"}


def normalize_optimizable_roles(
    optimize_prompts: bool | str | Iterable[str] | None,
) -> set[str]:
    """Normalize optimize_prompts into a set of allowed roles.

    Args:
        optimize_prompts:
            - True: allow all roles
            - False: allow none
            - None: default to system-only
            - str: single role (system/user/assistant) or "all"
            - iterable of roles

    Returns:
        Set of allowed roles (possibly empty).
    """
    if optimize_prompts is None:
        return {"system"}
    if isinstance(optimize_prompts, bool):
        return set(ALLOWED_PROMPT_ROLES) if optimize_prompts else set()
    if isinstance(optimize_prompts, str):
        role = optimize_prompts.strip().lower()
        if role == "all":
            return set(ALLOWED_PROMPT_ROLES)
        if role not in ALLOWED_PROMPT_ROLES:
            raise ValueError(
                f"optimize_prompts must be one of {sorted(ALLOWED_PROMPT_ROLES)}"
            )
        return {role}
    roles = {str(role).strip().lower() for role in optimize_prompts}
    unknown = [role for role in roles if role not in ALLOWED_PROMPT_ROLES]
    if unknown:
        raise ValueError(
            f"optimize_prompts roles must be from {sorted(ALLOWED_PROMPT_ROLES)}"
        )
    return roles


def apply_role_constraints(
    original_messages: list[dict[str, Any]],
    new_messages: list[dict[str, Any]],
    allowed_roles: set[str] | None,
) -> list[dict[str, Any]]:
    """Apply role constraints, preserving disallowed roles from originals."""
    if allowed_roles is None:
        return copy.deepcopy(new_messages)
    if not allowed_roles:
        return copy.deepcopy(original_messages)

    constrained: list[dict[str, Any]] = []
    for idx, original in enumerate(original_messages):
        role = original.get("role")
        if role not in allowed_roles:
            constrained.append(copy.deepcopy(original))
            continue
        if idx < len(new_messages) and new_messages[idx].get("role") == role:
            constrained.append(copy.deepcopy(new_messages[idx]))
        else:
            constrained.append(copy.deepcopy(original))
    return constrained


def count_disallowed_role_updates(
    original_messages: list[dict[str, Any]],
    new_messages: list[dict[str, Any]],
    allowed_roles: set[str] | None,
) -> int:
    """Count updates that target disallowed roles."""
    if allowed_roles is None:
        return 0
    if not allowed_roles:
        return sum(
            1 for msg in original_messages if msg.get("role") in ALLOWED_PROMPT_ROLES
        )
    count = 0
    for idx, original in enumerate(original_messages):
        role = original.get("role")
        if role in allowed_roles:
            continue
        if idx < len(new_messages) and new_messages[idx] != original:
            count += 1
    return count
