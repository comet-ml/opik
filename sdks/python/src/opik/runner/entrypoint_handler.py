import inspect
import logging
import sys
import typing
from typing import Any, Callable, List, Optional, Union

from . import dispatch
from .agents_registry import AgentInfo, Param, register_agent

LOGGER = logging.getLogger(__name__)

ALLOWED_TYPES = {str, int, float, bool, dict, list, type(None)}

ALLOWED_TYPE_NAMES = {"str", "int", "float", "bool", "dict", "list", "None"}


def handle_entrypoint(
    func: Callable[..., Any],
    name: str,
    project_name: Optional[str] = None,
    timeout: Optional[int] = None,
) -> Callable[..., Any]:
    """Register a tracked function as a runner agent and dispatch if invoked by the runner subprocess."""
    params = _extract_params(func)

    info = AgentInfo(
        name=name,
        executable=sys.executable,
        source_file=inspect.getfile(func),
        description=func.__doc__ or "",
        params=params,
        timeout=timeout,
        project=project_name,
    )

    register_agent(info)

    if dispatch.should_dispatch(name):
        dispatch.run_dispatch(func)
        sys.exit(0)

    return func


def _extract_params(func: Callable[..., Any]) -> List[Param]:
    sig = inspect.signature(func)
    params: List[Param] = []

    for param_name, param in sig.parameters.items():
        if param.kind == inspect.Parameter.POSITIONAL_ONLY:
            raise TypeError(
                f"Positional-only parameter '{param_name}' is not supported in entrypoint agents"
            )

        if param.kind == inspect.Parameter.VAR_POSITIONAL:
            raise TypeError(f"*{param_name} is not supported in entrypoint agents")

        if param.kind == inspect.Parameter.VAR_KEYWORD:
            continue

        annotation = param.annotation
        type_name = _resolve_type_name(annotation)

        if type_name is None:
            LOGGER.warning(
                "Parameter '%s' has no type hint, defaulting to str. "
                "Add a type annotation for accurate input handling.",
                param_name,
            )

        if type_name is not None and type_name not in ALLOWED_TYPE_NAMES:
            raise TypeError(
                f"Parameter '{param_name}' has unsupported type '{type_name}'. "
                f"Allowed types: {', '.join(sorted(ALLOWED_TYPE_NAMES))}"
            )

        params.append(Param(name=param_name, type=type_name or "str"))

    return params


def _resolve_type_name(annotation: Any) -> Optional[str]:
    """Unwrap generics (list[str] -> list, Optional[int] -> int) and return the base type name."""
    if annotation is inspect.Parameter.empty:
        return None

    if annotation is type(None):
        return "None"

    if isinstance(annotation, type) and annotation in ALLOWED_TYPES:
        return annotation.__name__

    if isinstance(annotation, str):
        return annotation

    # Unwrap Optional[X] (Union[X, None]) to X
    origin = typing.get_origin(annotation)
    if origin is Union:
        args = [a for a in typing.get_args(annotation) if a is not type(None)]
        if len(args) == 1:
            return _resolve_type_name(args[0])

    # Unwrap generics like list[str], Dict[str, int] to their origin type,
    # but validate inner types too so list[SomeModel] is rejected
    if origin is not None and origin in ALLOWED_TYPES:
        for arg in typing.get_args(annotation):
            arg_name = _resolve_type_name(arg)
            if arg_name is not None and arg_name not in ALLOWED_TYPE_NAMES:
                return arg_name  # bubble up for rejection by caller
        return origin.__name__

    return getattr(annotation, "__name__", str(annotation))
