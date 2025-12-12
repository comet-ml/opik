"""Utility helpers shared across LiteLLM models."""

from __future__ import annotations

from typing import Any, Callable, Dict, Set


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


def apply_model_specific_filters(
    model_name: str,
    params: Dict[str, Any],
    already_warned: Set[str],
    warn: Callable[[str, Any], None],
) -> None:
    """Adjust/drop params for specific model families before calling LiteLLM.

    Currently handles:
    - GPT-5: only honours temperature=1 and does not return log probabilities.
    - DashScope Qwen: enforces constraints for logprobs / top_logprobs
    """
    if model_name.startswith("gpt-5"):
        _apply_gpt5_filters(params, already_warned, warn)
        return

    if model_name.startswith("dashscope/"):
        _apply_qwen_dashscope_filters(params, already_warned, warn)
        return


def _apply_gpt5_filters(
    params: Dict[str, Any],
    already_warned: Set[str],
    warn: Callable[[str, Any], None],
) -> None:
    """Apply GPT-5 specific parameter filters.

    Only honours temperature=1 and does not return log probabilities.
    Removing those eagerly avoids provider errors while the callback surfaces a
    one-time warning to the caller.
    """

    unsupported: list[tuple[str, Any]] = []

    if "temperature" in params:
        value = params["temperature"]
        try:
            numeric_value = float(value)
        except (TypeError, ValueError):
            numeric_value = None
        if numeric_value is None or abs(numeric_value - 1.0) > 1e-6:
            unsupported.append(("temperature", value))

    for param in ("logprobs", "top_logprobs"):
        if param in params:
            unsupported.append((param, params[param]))

    _drop_unsupported_params_with_warning(
        params,
        unsupported,
        already_warned,
        warn,
    )


def _apply_qwen_dashscope_filters(
    params: Dict[str, Any],
    already_warned: Set[str],
    warn: Callable[[str, Any], None],
) -> None:
    """Apply Qwen/DashScope specific parameter filters.

    Does not return log probabilities.
    """

    unsupported: list[tuple[str, Any]] = []

    for param in ("logprobs", "top_logprobs"):
        if param in params:
            unsupported.append((param, params[param]))

    _drop_unsupported_params_with_warning(
        params,
        unsupported,
        already_warned,
        warn,
    )


def _drop_unsupported_params_with_warning(
    params: Dict[str, Any],
    unsupported_params: list[tuple[str, Any]],
    already_warned: Set[str],
    warn: Callable[[str, Any], None],
) -> None:
    """Remove unsupported params and emit warnings once per param name."""
    for param, value in unsupported_params:
        params.pop(param, None)
        if param in already_warned:
            continue
        warn(param, value)
        already_warned.add(param)
