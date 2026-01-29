from __future__ import annotations

import warnings
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import fields, is_dataclass
from typing import Any, Callable, Generator, TypeVar, overload

from opik import opik_context

from .client import ConfigClient, BackendUnavailableError
from .prompt import Prompt

T = TypeVar("T")

DEFAULT_BACKEND_URL = "http://localhost:5050"

_experiment_id: ContextVar[str | None] = ContextVar("experiment_id", default=None)


EXPERIMENT_HEADER = "X-Opik-Experiment-Id"


def _record_config_access(key: str, value: Any) -> None:
    """Record a config value access on the current span and trace."""
    # Convert Prompt to dict format for storage
    if isinstance(value, Prompt):
        store_value = {"prompt_name": value.name, "prompt": str(value)}
    else:
        store_value = value

    experiment_id = get_experiment_id()

    # Update the span
    span_data = opik_context.get_current_span_data()
    if span_data is not None:
        existing_metadata = span_data.metadata or {}
        opik_config = existing_metadata.get("opik_config", {
            "experiment_id": experiment_id,
            "values": {}
        })
        opik_config["values"][key] = store_value
        opik_context.update_current_span(metadata={"opik_config": opik_config})

    # Also update the trace
    trace_data = opik_context.get_current_trace_data()
    if trace_data is not None:
        existing_metadata = trace_data.metadata or {}
        opik_config = existing_metadata.get("opik_config", {
            "experiment_id": experiment_id,
            "values": {}
        })
        opik_config["values"][key] = store_value
        opik_context.update_current_trace(metadata={"opik_config": opik_config})


@contextmanager
def experiment_context(id_or_request: str | Any) -> Generator[None, None, None]:
    """
    Set the experiment ID for config lookups within this context.

    Accepts either an experiment_id string directly, or a request object
    with headers (Flask, FastAPI, etc.) to extract X-Opik-Experiment-Id.
    """
    if hasattr(id_or_request, "headers"):
        experiment_id = id_or_request.headers.get(EXPERIMENT_HEADER)
    else:
        experiment_id = id_or_request

    token = _experiment_id.set(experiment_id)
    try:
        yield
    finally:
        _experiment_id.reset(token)


def get_experiment_id() -> str | None:
    return _experiment_id.get()


def _get_configured_value(
    default: T,
    name: str,
    backend_url: str = DEFAULT_BACKEND_URL,
    strict: bool = True,
) -> T:
    trace_data = opik_context.get_current_trace_data()
    if trace_data is None and strict:
        raise ValueError(f"Reading '{name}' outside Opik trace context.")
    elif trace_data is None and not strict:
        warnings.warn(f"Reading '{name}' outside Opik trace context.", stacklevel=2)

    experiment_id = get_experiment_id()
    if experiment_id is None:
        return default

    client = ConfigClient(backend_url)
    try:
        values = client.get_values([name], experiment_id=experiment_id)
        if name in values:
            return values[name]
    except BackendUnavailableError:
        pass

    return default


@overload
def agent_config(
    cls_or_value: type[T],
    *,
    name: None = None,
    backend_url: str = DEFAULT_BACKEND_URL,
    strict: bool = True,
) -> type[T]: ...


@overload
def agent_config(
    cls_or_value: None = None,
    *,
    name: None = None,
    backend_url: str = DEFAULT_BACKEND_URL,
    strict: bool = True,
) -> Callable[[type[T]], type[T]]: ...


@overload
def agent_config(
    cls_or_value: T,
    *,
    name: str,
    backend_url: str = DEFAULT_BACKEND_URL,
    strict: bool = True,
) -> T: ...


def agent_config(
    cls_or_value: type[T] | T | None = None,
    *,
    name: str | None = None,
    backend_url: str = DEFAULT_BACKEND_URL,
    strict: bool = True,
) -> type[T] | Callable[[type[T]], type[T]] | T:
    """
    Make a dataclass or value configuration-aware.

    Usage:
        @agent_config
        @dataclass
        class Config:
            model: str = "gpt-4"

        model = agent_config("gpt-4", name="model")
    """
    if name is not None:
        return _get_configured_value(cls_or_value, name=name, backend_url=backend_url, strict=strict)

    def decorator(cls: type[T]) -> type[T]:
        if not is_dataclass(cls):
            raise TypeError(f"@agent_config requires a dataclass, got '{cls.__name__}'")

        field_names = {f.name for f in fields(cls)}
        original_getattribute = cls.__getattribute__
        original_init = cls.__init__
        client = ConfigClient(backend_url)

        def __init__(self: Any, *args: Any, **kwargs: Any) -> None:
            original_init(self, *args, **kwargs)
            # Register defaults with backend only if they don't exist
            for field_name in field_names:
                value = original_getattribute(self, field_name)
                if isinstance(value, Prompt):
                    prompt_data = {"prompt_name": value.name, "prompt": str(value)}
                    client.set_value(field_name, prompt_data, if_not_exists=True, is_default=True)
                else:
                    client.set_value(field_name, value, if_not_exists=True, is_default=True)

        cls.__init__ = __init__  # type: ignore[method-assign]

        def __getattribute__(self: Any, attr: str) -> Any:
            if attr.startswith("_") or attr not in field_names:
                return original_getattribute(self, attr)

            trace_data = opik_context.get_current_trace_data()
            if trace_data is None and strict:
                raise ValueError(f"Reading '{attr}' outside Opik trace context.")
            elif trace_data is None and not strict:
                warnings.warn(f"Reading '{attr}' outside Opik trace context.", stacklevel=2)

            original = original_getattribute(self, attr)
            lookup_key = attr
            experiment_id = get_experiment_id()

            # Always check backend for latest value (experiment_id adds override layer)
            result = original
            try:
                values = client.get_values([lookup_key], experiment_id=experiment_id)
                if lookup_key in values:
                    override = values[lookup_key]
                    if isinstance(original, Prompt):
                        if isinstance(override, dict) and "prompt" in override:
                            result = Prompt(name=override.get("prompt_name", original.name), prompt=override["prompt"])
                        elif isinstance(override, str):
                            result = Prompt(name=original.name, prompt=override)
                    else:
                        result = override
            except BackendUnavailableError:
                pass

            # Record this config access on the current span
            _record_config_access(attr, result)

            return result

        cls.__getattribute__ = __getattribute__  # type: ignore[method-assign]
        return cls

    if cls_or_value is None:
        return decorator
    return decorator(cls_or_value)
