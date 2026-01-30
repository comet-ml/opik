"""Agent config decorator with resolve-once-per-run caching."""

from __future__ import annotations

import warnings
from contextlib import contextmanager
from dataclasses import fields, is_dataclass
from typing import Any, Callable, Generator, TypeVar, get_type_hints, overload

from opik import opik_context

from .cache import (
    ConfigContext,
    ResolvedConfig,
    get_config_context,
    get_run_cache,
    set_config_context,
    clear_config_context,
)
from .client import ConfigClient, BackendUnavailableError
from .models import ConfigBehavior
from .prompt import Prompt
from .registration import KeyMetadata, queue_registration, set_registration_client
from .resolver import _decode_value, resolve_with_dedupe

T = TypeVar("T")

DEFAULT_BACKEND_URL = "http://localhost:5050"


@contextmanager
def config_context(
    mask_id: str | None = None,
    unit_id: str | None = None,
    project_id: str = "default",
    env: str = "prod",
) -> Generator[None, None, None]:
    """
    Set the config context for resolution within this scope.

    Args:
        mask_id: Experiment/variant identifier for config overrides
        unit_id: Bucketing identifier (auto-derived from trace_id if not set)
        project_id: Project identifier
        env: Environment (e.g., "prod", "staging")
    """
    # Auto-derive unit_id from trace_id if not provided
    if unit_id is None:
        trace_data = opik_context.get_current_trace_data()
        if trace_data is not None:
            unit_id = trace_data.id

    ctx = ConfigContext(
        project_id=project_id,
        env=env,
        mask_id=mask_id,
        unit_id=unit_id,
    )
    set_config_context(ctx)
    try:
        yield
    finally:
        clear_config_context()


@contextmanager
def experiment_context(experiment_id: str | None) -> Generator[None, None, None]:
    """
    Alias for config_context with experiment_id as mask_id.
    Provided for compatibility with existing code.
    """
    with config_context(mask_id=experiment_id):
        yield


def _get_default_for_prompt(value: Any) -> Any:
    """Convert a Prompt to a dict for storage."""
    if isinstance(value, Prompt):
        return {"prompt_name": value.name, "prompt": str(value)}
    return value


def _resolve_all_fields(
    cls: type,
    client: ConfigClient,
    field_defaults: dict[str, Any],
    type_hints: dict[str, type],
    behavior: ConfigBehavior,
) -> ResolvedConfig:
    """Resolve all fields for a config class in a single backend call."""
    ctx = get_config_context()
    cache = get_run_cache()
    qualname = cls.__qualname__

    # Check cache first
    cached = cache.get(ctx.project_id, ctx.mask_id, qualname)
    if cached is not None:
        return cached

    # Build cache key for deduplication
    cache_key = (ctx.project_id, ctx.env, ctx.mask_id, qualname)

    def do_resolve() -> ResolvedConfig:
        # Build the list of keys to resolve
        keys = [f"{qualname}.{name}" for name in field_defaults.keys()]

        try:
            response = client.resolve(
                project_id=ctx.project_id,
                env=ctx.env,
                keys=keys,
                mask_id=ctx.mask_id,
                unit_id=ctx.unit_id,
            )

            # Decode values with type hints
            resolved_values: dict[str, Any] = {}
            for field_name, default in field_defaults.items():
                key = f"{qualname}.{field_name}"
                type_hint = type_hints.get(field_name, type(default))

                # Check if key was resolved or is missing
                if key in response.get("resolved_values", {}):
                    raw_value = response["resolved_values"][key]
                    resolved_values[field_name] = _decode_value(raw_value, type_hint, default)
                else:
                    # Key is missing from backend, use default
                    resolved_values[field_name] = default

            result = ResolvedConfig(
                values=resolved_values,
                value_ids=response.get("resolved_value_ids", {}),
                assigned_variant=response.get("assigned_variant"),
                revision=0,  # SQLite backend doesn't use revision
            )

        except BackendUnavailableError:
            if behavior.on_backend_unavailable == "error":
                raise
            # Use fallback defaults
            result = ResolvedConfig(
                values=dict(field_defaults),
                value_ids={},
                assigned_variant=None,
                revision=0,
            )

        # Store in cache
        cache.set(ctx.project_id, ctx.mask_id, qualname, result)

        # Log config to trace for UI visibility
        _log_config_to_trace(ctx.mask_id, result.assigned_variant, result.values)

        return result

    return resolve_with_dedupe(cache_key, do_resolve)


def _log_config_to_trace(
    mask_id: str | None,
    assigned_variant: str | None,
    resolved_values: dict[str, Any],
) -> None:
    """Log config data to the current trace for UI visibility."""
    trace_data = opik_context.get_current_trace_data()
    if trace_data is not None:
        existing_metadata = trace_data.metadata or {}
        opik_context.update_current_trace(
            metadata={
                **existing_metadata,
                "opik_config": {
                    "experiment_id": mask_id,
                    "assigned_variant": assigned_variant,
                    "values": resolved_values,
                },
            }
        )


@overload
def agent_config(
    cls: type[T],
    *,
    backend_url: str = DEFAULT_BACKEND_URL,
    behavior: ConfigBehavior | None = None,
) -> type[T]: ...


@overload
def agent_config(
    cls: None = None,
    *,
    backend_url: str = DEFAULT_BACKEND_URL,
    behavior: ConfigBehavior | None = None,
) -> Callable[[type[T]], type[T]]: ...


def agent_config(
    cls: type[T] | None = None,
    *,
    backend_url: str = DEFAULT_BACKEND_URL,
    behavior: ConfigBehavior | None = None,
) -> type[T] | Callable[[type[T]], type[T]]:
    """
    Make a dataclass configuration-aware with resolve-once-per-run caching.

    Usage:
        @agent_config
        @dataclass
        class Config:
            model: str = "gpt-4"
            temperature: float = 0.7

        # In your agent:
        with config_context(mask_id="experiment-123"):
            config = Config()
            print(config.model)  # Resolved from backend, cached for this run
    """
    if behavior is None:
        behavior = ConfigBehavior()

    def decorator(cls: type[T]) -> type[T]:
        if not is_dataclass(cls):
            raise TypeError(f"@agent_config requires a dataclass, got '{cls.__name__}'")

        field_info = {f.name: f for f in fields(cls)}
        field_names = set(field_info.keys())
        original_getattribute = cls.__getattribute__
        original_init = cls.__init__

        # Get type hints for decoding
        try:
            type_hints = get_type_hints(cls)
        except Exception:
            type_hints = {}

        client = ConfigClient(backend_url)
        set_registration_client(client, "default")

        def __init__(self: Any, *args: Any, **kwargs: Any) -> None:
            original_init(self, *args, **kwargs)

            # Queue key registrations (non-blocking)
            for field_name in field_names:
                value = original_getattribute(self, field_name)
                key = f"{cls.__qualname__}.{field_name}"
                type_hint = type_hints.get(field_name, type(value))

                queue_registration(
                    KeyMetadata(
                        key=key,
                        type_hint=str(type_hint),
                        default_value=_get_default_for_prompt(value),
                        class_name=cls.__qualname__,
                        field_name=field_name,
                    )
                )

        cls.__init__ = __init__  # type: ignore[method-assign]

        def __getattribute__(self: Any, attr: str) -> Any:
            if attr.startswith("_") or attr not in field_names:
                return original_getattribute(self, attr)

            # Check for trace context
            trace_data = opik_context.get_current_trace_data()
            if trace_data is None and behavior.strict_context:
                raise ValueError(f"Reading '{attr}' outside Opik trace context.")
            elif trace_data is None and not behavior.strict_context:
                warnings.warn(f"Reading '{attr}' outside Opik trace context.", stacklevel=2)

            # Get field defaults
            field_defaults = {}
            for name in field_names:
                field_defaults[name] = original_getattribute(self, name)

            # Resolve all fields (cached)
            resolved = _resolve_all_fields(cls, client, field_defaults, type_hints, behavior)

            return resolved.values.get(attr, original_getattribute(self, attr))

        cls.__getattribute__ = __getattribute__  # type: ignore[method-assign]
        return cls

    if cls is None:
        return decorator
    return decorator(cls)
