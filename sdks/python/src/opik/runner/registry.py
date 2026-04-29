"""In-memory entrypoint registry for the local runner."""

import dataclasses
import inspect
import logging
import threading
import typing
from typing import Any, Callable, Dict, List

from opik.api_objects import type_helpers
from opik.rest_api.types.param_presence import ParamPresence

logger = logging.getLogger(__name__)

_lock = threading.Lock()
REGISTRY: Dict[str, Dict[str, Any]] = {}
_listeners: List[Callable[[str], None]] = []


@dataclasses.dataclass
class Param:
    name: str
    type: str = "string"
    presence: ParamPresence = "required"


def register(
    name: str,
    func: Callable,
    project: str,
    params: List[Param],
    docstring: str,
) -> None:
    with _lock:
        REGISTRY[name] = {
            "func": func,
            "name": name,
            "project": project,
            "params": params,
            "docstring": docstring,
        }
        listeners = list(_listeners)

    for listener in listeners:
        listener(name)


def on_register(listener: Callable[[str], None]) -> None:
    with _lock:
        _listeners.append(listener)


def get_all() -> Dict[str, Dict[str, Any]]:
    with _lock:
        return dict(REGISTRY)


def extract_params(fn: Callable) -> List[Param]:
    sig = inspect.signature(fn)

    # Resolve string annotations (e.g. from `from __future__ import annotations`)
    try:
        hints = typing.get_type_hints(fn)
    except Exception:
        hints = {}

    params: List[Param] = []
    unresolved: List[str] = []
    for param_name, param in sig.parameters.items():
        ann = hints.get(param_name, param.annotation)
        if ann is inspect.Parameter.empty:
            type_name = "string"
        else:
            inner = type_helpers.unwrap_optional(ann)
            if inner is not None:
                ann = inner
            try:
                type_name = type_helpers.python_type_to_backend_type(ann)
            except TypeError:
                type_name = "string"
                unresolved.append(param_name)
        presence: ParamPresence = (
            "required" if param.default is inspect.Parameter.empty else "optional"
        )
        params.append(Param(name=param_name, type=type_name, presence=presence))
    if unresolved:
        logger.warning(
            "Could not resolve type for parameter(s) %s in %r. "
            "These parameters will default to 'string' and cannot be modified via the UI. "
            "Consider using a supported type (str, int, float, bool) or choosing a different entrypoint.",
            unresolved,
            getattr(fn, "__name__", fn),
        )
    return params
