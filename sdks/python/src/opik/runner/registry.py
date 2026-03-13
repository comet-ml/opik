"""In-memory entrypoint registry for the local runner."""

import dataclasses
import inspect
import threading
from typing import Any, Callable, Dict, List

_lock = threading.Lock()
REGISTRY: Dict[str, Dict[str, Any]] = {}


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
