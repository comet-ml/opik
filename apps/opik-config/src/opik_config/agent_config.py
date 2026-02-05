"""Agent config decorator with resolve-once-per-run caching."""

from __future__ import annotations

import warnings
from contextlib import contextmanager
from dataclasses import fields, is_dataclass
from typing import Annotated, Any, Callable, Generator, TypeVar, get_args, get_origin, get_type_hints, overload

from opik import opik_context

from opik import Prompt

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
from .registration import KeyMetadata, queue_registration, set_registration_client
from .resolver import _decode_value, resolve_with_dedupe

T = TypeVar("T")

DEFAULT_BACKEND_URL = "http://localhost:5050"


def _extract_annotations(type_hint: type) -> list[str]:
    """Extract all string annotations from an Annotated type."""
    if get_origin(type_hint) is Annotated:
        args = get_args(type_hint)
        return [arg for arg in args[1:] if isinstance(arg, str)]
    return []


def _unwrap_annotated(type_hint: type) -> type:
    """Get the base type from an Annotated type, or return as-is."""
    if get_origin(type_hint) is Annotated:
        return get_args(type_hint)[0]
    return type_hint


def _normalize_annotations(annotations: str | list[str] | None) -> list[str]:
    """Normalize annotations to a list."""
    if annotations is None:
        return []
    if isinstance(annotations, str):
        return [annotations]
    return list(annotations)


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
    # Ensure mask_id is a string if provided
    if mask_id is not None and not isinstance(mask_id, str):
        mask_id = str(mask_id)

    # Auto-derive unit_id from trace_id if not provided
    if unit_id is None:
        trace_data = opik_context.get_current_trace_data()
        if trace_data is not None:
            unit_id = str(trace_data.id)
    elif not isinstance(unit_id, str):
        unit_id = str(unit_id)

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
def experiment_context(experiment_id_or_request: str | None = None) -> Generator[None, None, None]:
    """
    Set up config context for an experiment.

    Args:
        experiment_id_or_request: Either an experiment ID string, or a Flask/Starlette
            request object (will extract X-Opik-Experiment-Id header automatically).
    """
    experiment_id: str | None = None

    if experiment_id_or_request is None:
        experiment_id = None
    elif isinstance(experiment_id_or_request, str):
        experiment_id = experiment_id_or_request
    else:
        # Try to extract from request object (Flask, Starlette, etc.)
        headers = getattr(experiment_id_or_request, "headers", None)
        if headers is not None:
            experiment_id = headers.get("X-Opik-Experiment-Id")

    with config_context(mask_id=experiment_id):
        yield


def _get_default_for_prompt(value: Any) -> Any:
    """Convert a Prompt to a dict for storage."""
    if isinstance(value, Prompt):
        return {"prompt_name": value.name, "prompt": value.prompt}
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
                experiment_type=response.get("experiment_type"),
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
        _log_config_to_trace(
            ctx.mask_id,
            result.assigned_variant,
            result.experiment_type,
            result.values,
            client=client,
            project_id=ctx.project_id,
        )

        return result

    return resolve_with_dedupe(cache_key, do_resolve)


def _make_json_safe(value: Any) -> Any:
    """Convert value to JSON-serializable form."""
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, Prompt):
        return {"prompt_name": value.name, "prompt": value.prompt}
    if isinstance(value, dict):
        return {k: _make_json_safe(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_make_json_safe(v) for v in value]
    # For non-serializable objects, convert to string representation
    return str(value)


def _get_prompt_versions_for_trace(
    client: ConfigClient,
    project_id: str,
    resolved_values: dict[str, Any],
) -> dict[str, Any]:
    """Look up Opik version info for prompts in resolved values."""
    prompt_versions: dict[str, Any] = {}

    for field_name, value in resolved_values.items():
        prompt_name: str | None = None

        if isinstance(value, Prompt):
            prompt_name = value.name
        elif isinstance(value, dict) and "prompt_name" in value:
            prompt_name = value["prompt_name"]

        if prompt_name:
            version_info = client.get_prompt_version_info(prompt_name, project_id)
            if version_info and version_info.get("commit"):
                prompt_versions[prompt_name] = {
                    "commit": version_info.get("commit"),
                    "opik_prompt_id": version_info.get("opik_prompt_id"),
                    "opik_version_id": version_info.get("opik_version_id"),
                }

    return prompt_versions


def _log_config_to_trace(
    mask_id: str | None,
    assigned_variant: str | None,
    experiment_type: str | None,
    resolved_values: dict[str, Any],
    client: ConfigClient | None = None,
    project_id: str = "default",
) -> None:
    """Log config data to the current trace for UI visibility."""
    try:
        trace_data = opik_context.get_current_trace_data()
        if trace_data is not None:
            safe_values = {k: _make_json_safe(v) for k, v in resolved_values.items()}

            tags: list[str] = []
            if mask_id:
                tags.append(f"experiment:{mask_id}")

            config_metadata: dict[str, Any] = {
                "experiment_id": mask_id,
                "experiment_type": experiment_type,
                "assigned_variant": assigned_variant,
                "values": safe_values,
            }

            if client:
                prompt_versions = _get_prompt_versions_for_trace(
                    client, project_id, resolved_values
                )
                if prompt_versions:
                    config_metadata["prompt_versions"] = prompt_versions

            opik_context.update_current_trace(
                metadata={"opik_config": config_metadata},
                tags=tags if tags else None,
            )
    except Exception:
        pass  # Don't fail the main flow if logging fails


def _resolve_variable(
    value: T,
    name: str,
    backend_url: str,
    behavior: ConfigBehavior,
    annotations: list[str] | None = None,
) -> T:
    """Resolve a single variable from the config backend."""
    ctx = get_config_context()
    cache = get_run_cache()
    client = ConfigClient(backend_url)
    set_registration_client(client, "default")

    # Check cache first
    cached = cache.get(ctx.project_id, ctx.mask_id, name)
    if cached is not None:
        return cached.values.get(name, value)

    # Queue registration
    queue_registration(
        KeyMetadata(
            key=name,
            type_hint=str(type(value)),
            default_value=_get_default_for_prompt(value),
            class_name=None,
            field_name=name,
            annotations=annotations,
        )
    )

    # Build cache key for deduplication
    cache_key = (ctx.project_id, ctx.env, ctx.mask_id, name)

    def do_resolve() -> ResolvedConfig:
        try:
            response = client.resolve(
                project_id=ctx.project_id,
                env=ctx.env,
                keys=[name],
                mask_id=ctx.mask_id,
                unit_id=ctx.unit_id,
            )

            resolved_value = value
            if name in response.get("resolved_values", {}):
                raw_value = response["resolved_values"][name]
                resolved_value = _decode_value(raw_value, type(value), value)

            result = ResolvedConfig(
                values={name: resolved_value},
                value_ids=response.get("resolved_value_ids", {}),
                assigned_variant=response.get("assigned_variant"),
                experiment_type=response.get("experiment_type"),
                revision=0,
            )

        except BackendUnavailableError:
            if behavior.on_backend_unavailable == "error":
                raise
            result = ResolvedConfig(
                values={name: value},
                value_ids={},
                assigned_variant=None,
                revision=0,
            )

        cache.set(ctx.project_id, ctx.mask_id, name, result)

        _log_config_to_trace(
            ctx.mask_id,
            result.assigned_variant,
            result.experiment_type,
            result.values,
            client=client,
            project_id=ctx.project_id,
        )

        return result

    resolved = resolve_with_dedupe(cache_key, do_resolve)
    return resolved.values.get(name, value)


@overload
def agent_config(
    cls: type[T],
    *,
    annotations: str | list[str] | None = None,
    backend_url: str = DEFAULT_BACKEND_URL,
    behavior: ConfigBehavior | None = None,
) -> type[T]: ...


@overload
def agent_config(
    cls: None = None,
    *,
    annotations: str | list[str] | None = None,
    backend_url: str = DEFAULT_BACKEND_URL,
    behavior: ConfigBehavior | None = None,
) -> Callable[[type[T]], type[T]]: ...


@overload
def agent_config(
    cls: T,
    *,
    name: str,
    annotations: str | list[str] | None = None,
    backend_url: str = DEFAULT_BACKEND_URL,
    behavior: ConfigBehavior | None = None,
) -> T: ...


def agent_config(
    cls: type[T] | T | None = None,
    *,
    name: str | None = None,
    annotations: str | list[str] | None = None,
    backend_url: str = DEFAULT_BACKEND_URL,
    behavior: ConfigBehavior | None = None,
) -> type[T] | T | Callable[[type[T]], type[T]]:
    """
    Make a dataclass or variable configuration-aware with resolve-once-per-run caching.

    Usage as decorator:
        @agent_config
        @dataclass
        class Config:
            model: str = "gpt-4"
            temperature: float = 0.7

        with config_context(mask_id="experiment-123"):
            config = Config()
            print(config.model)  # Resolved from backend, cached for this run

    Usage on variables:
        model = "gpt-4"
        model = agent_config(model, name="model")  # Resolved from backend
    """
    if behavior is None:
        behavior = ConfigBehavior()

    class_annotations = _normalize_annotations(annotations)

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

                # Extract annotations from Annotated type and combine with class-level
                field_annots = _extract_annotations(type_hint)
                combined_annots = class_annotations + field_annots
                base_type = _unwrap_annotated(type_hint)

                queue_registration(
                    KeyMetadata(
                        key=key,
                        type_hint=str(base_type),
                        default_value=_get_default_for_prompt(value),
                        class_name=cls.__qualname__,
                        field_name=field_name,
                        annotations=combined_annots if combined_annots else None,
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

    # Variable mode: agent_config(var, name='var')
    if name is not None:
        var_annotations = _normalize_annotations(annotations)
        return _resolve_variable(
            cls, name, backend_url, behavior,
            annotations=var_annotations if var_annotations else None,
        )

    if cls is None:
        return decorator
    return decorator(cls)
