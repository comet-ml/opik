"""In-memory entrypoint registry for the local runner."""

import dataclasses
import inspect
import threading
from typing import Any, Callable, Dict, List

_lock = threading.Lock()
REGISTRY: Dict[str, Dict[str, Any]] = {}
_listeners: List[Callable[[str], None]] = []


@dataclasses.dataclass
class Param:
    name: str
    type: str = "str"


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
    params: List[Param] = []
    for param_name, param in sig.parameters.items():
        if param.annotation is inspect.Parameter.empty:
            type_name = "str"
        else:
            ann = param.annotation
            type_name = ann.__name__ if hasattr(ann, "__name__") else str(ann)
        params.append(Param(name=param_name, type=type_name))
    return params
