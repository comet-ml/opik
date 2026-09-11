"""General helper utilities for benchmark modules."""

import json
from typing import Any


def make_serializable(obj: Any) -> Any:
    """Recursively convert objects to JSON-serializable types."""
    if isinstance(obj, dict):
        return {k: make_serializable(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [make_serializable(item) for item in obj]
    if hasattr(obj, "model_dump"):  # Pydantic v2
        return make_serializable(obj.model_dump())
    if hasattr(obj, "dict"):  # Pydantic v1
        return make_serializable(obj.dict())
    if hasattr(obj, "__dict__"):
        return make_serializable(obj.__dict__)

    try:
        json.dumps(obj)
        return obj
    except (TypeError, ValueError):
        return str(obj)
