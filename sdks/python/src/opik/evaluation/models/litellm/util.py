"""Utility helpers shared across LiteLLM models."""

from __future__ import annotations

from typing import Any, Dict


def normalise_choice(choice: Any) -> Dict[str, Any]:
    """Produce a dict view of a LiteLLM choice regardless of response type.

    LiteLLM may return raw dicts, Pydantic models, or dataclasses. Normalising to a
    dict here keeps downstream parsing logic consistent and backwards compatible with
    older client versions.
    """

    if isinstance(choice, dict):
        return choice
    if hasattr(choice, "model_dump") and callable(choice.model_dump):
        try:
            return choice.model_dump()
        except TypeError:
            pass
    normalised: Dict[str, Any] = {}
    message = getattr(choice, "message", None)
    if message is not None:
        normalised["message"] = message
    logprobs = getattr(choice, "logprobs", None)
    if logprobs is not None:
        normalised["logprobs"] = logprobs
    return normalised
