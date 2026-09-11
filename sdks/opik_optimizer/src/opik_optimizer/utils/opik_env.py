from __future__ import annotations

import logging
import os
import threading
from typing import Any

logger = logging.getLogger(__name__)

_ENV_LOCK = threading.Lock()


def set_project_name_env(project_name: Any) -> None:
    """Set OPIK_PROJECT_NAME once per process to avoid thread races."""
    if project_name is None:
        return
    value = str(project_name)
    with _ENV_LOCK:
        existing = os.environ.get("OPIK_PROJECT_NAME")
        if existing is None:
            os.environ["OPIK_PROJECT_NAME"] = value
            return
        if existing != value:
            logger.warning(
                "OPIK_PROJECT_NAME already set to %s; ignoring new value %s.",
                existing,
                value,
            )
