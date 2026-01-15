"""Shared constants and helpers for the Opik Optimizer SDK."""

import os
import re

from typing import Optional

DEFAULT_PROJECT_NAME = "Optimization"

def resolve_project_name(project_name: str | None = None) -> str:
    """
    Determine the project name using (in order):
    1) Explicit parameter if provided and non-empty
    2) OPIK_PROJECT_NAME environment variable if set
    3) DEFAULT_PROJECT_NAME fallback

    Returns a sanitized name safe for API usage.
    """
    candidate = (project_name or "").strip()
    if not candidate:
        env_value = (os.environ.get("OPIK_PROJECT_NAME") or "").strip()
        candidate = env_value or DEFAULT_PROJECT_NAME

    # Basic sanitization: strip control chars and trim length.
    sanitized = re.sub(r"[^\w\s\-\.\+]", "", candidate).strip()
    return sanitized or DEFAULT_PROJECT_NAME

