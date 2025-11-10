"""JSON serialization utilities for benchmark results."""

import json
from typing import Any


def make_serializable(obj: Any) -> Any:
    """Recursively convert objects to JSON-serializable types."""
    if isinstance(obj, dict):
        return {k: make_serializable(v) for k, v in obj.items()}
    elif isinstance(obj, (list, tuple)):
        return [make_serializable(item) for item in obj]
    elif hasattr(obj, "model_dump"):  # Pydantic v2
        return make_serializable(obj.model_dump())
    elif hasattr(obj, "dict"):  # Pydantic v1
        return make_serializable(obj.dict())
    elif hasattr(obj, "__dict__"):
        return make_serializable(obj.__dict__)
    else:
        # Try to convert to string if not serializable
        try:
            json.dumps(obj)
            return obj
        except (TypeError, ValueError):
            return str(obj)
