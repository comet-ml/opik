import functools
import hashlib

from typing import Optional

import opik.config
import opik.environment


def get_id() -> Optional[str]:
    """
    The workspace name serves as a user identifier.
    If workspace is default, then we try to compute the identifier as a hash from the hostname
    and username.
    It is not a strict relation, host machine might change or the user might accidentally pass incorrect workspace.
    But we use it as an approximation to have a better visibility of the amount of users affected by some error.
    """
    config = opik.config.OpikConfig()

    if config.workspace != opik.config.OPIK_WORKSPACE_DEFAULT_NAME:
        return config.workspace

    hashed_part = _compute_hash(
        opik.environment.get_hostname() + opik.environment.get_user()
    )

    identifier = f"{opik.config.OPIK_WORKSPACE_DEFAULT_NAME}_{hashed_part}"

    return identifier


@functools.lru_cache
def _compute_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()
