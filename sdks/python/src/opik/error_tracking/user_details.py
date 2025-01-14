import functools
import hashlib

from typing import Optional

import opik.config
import opik.environment


def get_id() -> Optional[str]:
    """
    workspace + hashed API key or hashed hostname serves as a user identifier.
    It is not a strict relation, because api key might change, host machine might change
    or user might accidentally pass incorrect one. But
    we use it as an approximation to have better
    visibility on amount of users affected by some error.
    """
    config = opik.config.OpikConfig()

    if config.workspace != opik.config.OPIK_WORKSPACE_DEFAULT_NAME:
        return config.workspace

    hashed_part = (
        _compute_hash(config.api_key)
        if config.api_key is not None
        else _compute_hash(
            opik.environment.get_hostname() + opik.environment.get_user()
        )
    )

    identifier = f"{config.workspace}_{hashed_part}"

    return identifier


@functools.lru_cache
def _compute_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()
