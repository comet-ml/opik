"""Resolution logic with type decoding and concurrency deduplication."""

from __future__ import annotations

import threading
import warnings
from dataclasses import dataclass
from typing import Any, Callable, TypeVar, get_args, get_origin

from .cache import ResolvedConfig
from .prompt import Prompt

T = TypeVar("T")


@dataclass
class InflightRequest:
    """Tracks an in-flight resolution request."""
    event: threading.Event
    result: ResolvedConfig | None = None
    error: Exception | None = None


# Concurrency deduplication
_inflight: dict[tuple, InflightRequest] = {}
_inflight_lock = threading.Lock()


def _decode_value(raw: Any, type_hint: type, default: Any) -> Any:
    """
    Decode a raw JSON value to the expected Python type.
    Falls back to default on decode/validation failure with warning.
    """
    if raw is None:
        return default

    origin = get_origin(type_hint)
    args = get_args(type_hint)

    try:
        # Handle Optional[T] (Union[T, None])
        if origin is type(None) or (hasattr(origin, "__origin__") and origin.__origin__ is type(None)):
            return raw

        # Handle Prompt type
        if type_hint is Prompt or (isinstance(type_hint, type) and issubclass(type_hint, Prompt)):
            if isinstance(raw, dict) and "prompt" in raw:
                return Prompt(name=raw.get("prompt_name", ""), prompt=raw["prompt"])
            elif isinstance(raw, str):
                return Prompt(name=default.name if isinstance(default, Prompt) else "", prompt=raw)
            return default

        # Handle basic types
        if type_hint is str:
            return str(raw) if not isinstance(raw, str) else raw
        if type_hint is int:
            return int(raw)
        if type_hint is float:
            return float(raw)
        if type_hint is bool:
            if isinstance(raw, bool):
                return raw
            if isinstance(raw, str):
                return raw.lower() in ("true", "1", "yes")
            return bool(raw)

        # Handle list[T]
        if origin is list:
            if not isinstance(raw, list):
                warnings.warn(f"Expected list, got {type(raw).__name__}, using default")
                return default
            if args:
                item_type = args[0]
                return [_decode_value(item, item_type, None) for item in raw]
            return raw

        # Handle dict[K, V]
        if origin is dict:
            if not isinstance(raw, dict):
                warnings.warn(f"Expected dict, got {type(raw).__name__}, using default")
                return default
            return raw

        # Handle Optional[T]
        if origin is type(None):
            return raw

        # Check for Union types (Optional is Union[T, None])
        import typing
        if hasattr(typing, "Union") and origin is getattr(typing, "Union", None):
            # Try each type in the union
            for arg in args:
                if arg is type(None):
                    continue
                try:
                    return _decode_value(raw, arg, default)
                except (ValueError, TypeError):
                    continue
            return default

        # Default: return raw value
        return raw

    except (ValueError, TypeError, KeyError) as e:
        warnings.warn(f"Failed to decode value: {e}, using default")
        return default


def resolve_with_dedupe(
    cache_key: tuple,
    resolver: Callable[[], ResolvedConfig],
) -> ResolvedConfig:
    """
    Resolve a config with concurrency deduplication.
    If another thread is resolving the same key, wait for its result.
    """
    with _inflight_lock:
        if cache_key in _inflight:
            # Another thread is resolving this key, wait for it
            inflight = _inflight[cache_key]
        else:
            # We'll do the resolution
            inflight = InflightRequest(event=threading.Event())
            _inflight[cache_key] = inflight
            inflight = None  # Signal that we should do the work

    if inflight is not None:
        # Wait for the other thread
        inflight.event.wait()
        if inflight.error:
            raise inflight.error
        return inflight.result  # type: ignore

    # We're doing the resolution
    try:
        result = resolver()
        with _inflight_lock:
            req = _inflight[cache_key]
            req.result = result
            req.event.set()
            del _inflight[cache_key]
        return result
    except Exception as e:
        with _inflight_lock:
            req = _inflight[cache_key]
            req.error = e
            req.event.set()
            del _inflight[cache_key]
        raise
