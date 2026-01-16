"""Shared constants and helpers for the Opik Optimizer SDK."""

import os
import re

# Public API Defaults
DEFAULT_PROJECT_NAME = "Optimization"
DEFAULT_EVAL_THREADS = 12

# Internal API Defaults
MIN_EVAL_THREADS = 1
MAX_EVAL_THREADS = 64

# Schema and Types
OPTIMIZATION_RESULT_SCHEMA_VERSION = "v1"


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


def normalize_eval_threads(n_threads: int | None) -> int:
    """
    Clamp and sanitize thread counts for evaluator calls.

    Ensures a positive integer within configured bounds and uses defaults
    when values are missing or invalid.
    """
    candidate = n_threads if n_threads is not None else DEFAULT_EVAL_THREADS
    try:
        normalized = int(candidate)
    except (TypeError, ValueError):
        normalized = DEFAULT_EVAL_THREADS

    normalized = max(MIN_EVAL_THREADS, normalized)
    normalized = min(MAX_EVAL_THREADS, normalized)
    return normalized
