"""@entrypoint decorator - tracing + self-registration + auto-dispatch."""

import inspect
import json
import logging
import os
import sys
from typing import Any, Callable, Dict, List, Optional

from ..decorator.tracker import track

LOGGER = logging.getLogger(__name__)

_REGISTRY: Dict[str, Dict[str, Any]] = {}


def entrypoint(
    func: Optional[Callable] = None,
    *,
    name: Optional[str] = None,
    project: Optional[str] = None,
    type: str = "general",
    tags: Optional[List[str]] = None,
    metadata: Optional[Dict[str, Any]] = None,
    capture_input: bool = True,
    capture_output: bool = True,
    project_name: Optional[str] = None,
) -> Callable:
    """Mark a function as a runner entrypoint with tracing.

    Does three things:
    1. Wraps with @track for tracing
    2. Self-registers to ~/.opik/agents.json (records sys.executable, file, project)
    3. If OPIK_AGENT env var matches this function's name, auto-dispatches
       (reads inputs from stdin, calls function, writes result to stdout, exits)

    Can be used with or without arguments:
        @entrypoint
        def my_agent(question: str) -> str: ...

        @entrypoint(name="custom", project="my-project")
        def my_agent(question: str) -> str: ...
    """
    def decorator(fn: Callable) -> Callable:
        agent_name = name or fn.__name__
        agent_project = project or "default"

        tracked_fn = track(
            name=agent_name,
            type=type,
            tags=tags,
            metadata=metadata,
            capture_input=capture_input,
            capture_output=capture_output,
            project_name=project_name or agent_project,
        )(fn)

        source_file = os.path.abspath(inspect.getfile(fn))

        params = _extract_params(fn)

        _REGISTRY[agent_name] = {
            "func": tracked_fn,
            "name": agent_name,
            "project": agent_project,
            "python": sys.executable,
            "file": source_file,
            "params": params,
        }

        _self_register(agent_name, agent_project, source_file, params)

        # Single-function debug dispatch
        debug_func = os.environ.get("OPIK_DEBUG_FUNC")
        if debug_func and debug_func == fn.__name__:
            _dispatch_debug_function(fn)

        # Auto-dispatch: if runner is calling us to execute this specific agent
        target_agent = os.environ.get("OPIK_AGENT")
        if target_agent == agent_name:
            _dispatch(tracked_fn)

        tracked_fn._entrypoint_name = agent_name  # type: ignore[attr-defined]
        tracked_fn._entrypoint_project = agent_project  # type: ignore[attr-defined]
        return tracked_fn

    if func is not None:
        return decorator(func)
    return decorator


def get_registry() -> Dict[str, Dict[str, Any]]:
    return _REGISTRY


def _extract_params(fn: Callable) -> List[Dict[str, str]]:
    """Extract parameter names and type annotations from a function."""
    sig = inspect.signature(fn)
    params = []
    for param_name, param in sig.parameters.items():
        if param.annotation is inspect.Parameter.empty:
            type_name = "str"
        else:
            ann = param.annotation
            type_name = ann.__name__ if hasattr(ann, "__name__") else str(ann)
        params.append({"name": param_name, "type": type_name})
    return params


def _self_register(agent_name: str, project: str, source_file: str, params: List[Dict[str, str]]) -> None:
    """Write this agent's info to ~/.opik/agents.json."""
    from .config import register_agent

    try:
        register_agent(
            name=agent_name,
            python=sys.executable,
            file=source_file,
            project=project,
            params=params,
        )
    except Exception:
        LOGGER.debug("Failed to self-register agent '%s'", agent_name, exc_info=True)


def _dispatch(func: Callable) -> None:
    """Execute the agent function with inputs from stdin, write result to file or stdout, exit."""
    from . import graph_capture

    graph_file = os.environ.get("OPIK_DEBUG_GRAPH_FILE")
    if graph_file:
        graph_capture.activate()

    try:
        raw = sys.stdin.read()
        inputs = json.loads(raw) if raw.strip() else {}
    except (json.JSONDecodeError, EOFError):
        inputs = {}

    try:
        result = func(**inputs) if isinstance(inputs, dict) else func(inputs)
        if not isinstance(result, (dict, str, int, float, bool, list)):
            result = str(result)
        output = {"result": result}
    except Exception as e:
        output = {"error": f"{type(e).__name__}: {e}"}

    if graph_file:
        graph_capture.deactivate()
        try:
            with open(graph_file, "w") as f:
                json.dump(graph_capture.get_graph_dicts(), f)
        except Exception:
            LOGGER.warning("Failed to write graph file: %s", graph_file, exc_info=True)

    result_file = os.environ.get("OPIK_RESULT_FILE")
    if result_file:
        with open(result_file, "w") as f:
            f.write(json.dumps(output))
    else:
        sys.stdout.write(json.dumps(output))
        sys.stdout.flush()
    sys.exit(0)


def _dispatch_debug_function(func: Callable) -> None:
    """Execute a single function for debug stepping.

    Env vars:
    - OPIK_DEBUG_FUNC: function name (matched above)
    - OPIK_DEBUG_INPUTS: JSON-serialized inputs dict
    - OPIK_DEBUG_TRACE_ID: trace to attach span to
    - OPIK_DEBUG_PARENT_SPAN_ID: parent span
    """
    from .. import context_storage
    from ..api_objects.span import SpanData
    from ..api_objects.trace import TraceData

    inputs_str = os.environ.get("OPIK_DEBUG_INPUTS", "{}")
    trace_id = os.environ.get("OPIK_DEBUG_TRACE_ID", "")
    parent_span_id = os.environ.get("OPIK_DEBUG_PARENT_SPAN_ID")
    project_name = os.environ.get("OPIK_DEBUG_PROJECT", "default")

    try:
        inputs = json.loads(inputs_str)
    except json.JSONDecodeError:
        inputs = {}

    if trace_id:
        trace_data = TraceData(
            id=trace_id,
            name="debug",
            project_name=project_name,
        )
        context_storage.set_trace_data(trace_data)

    if parent_span_id and trace_id:
        parent_span = SpanData(
            id=parent_span_id,
            trace_id=trace_id,
            name="debug-parent",
            project_name=project_name,
        )
        context_storage.add_span_data(parent_span)

    try:
        result = func(**inputs) if isinstance(inputs, dict) else func(inputs)
        if not isinstance(result, (dict, str, int, float, bool, list)):
            result = str(result)
        output = {"result": result}
    except Exception as e:
        output = {"error": f"{type(e).__name__}: {e}"}

    result_file = os.environ.get("OPIK_RESULT_FILE")
    if result_file:
        with open(result_file, "w") as f:
            f.write(json.dumps(output))
    else:
        sys.stdout.write(json.dumps(output))
        sys.stdout.flush()
    sys.exit(0)
