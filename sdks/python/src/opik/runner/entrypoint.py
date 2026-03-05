"""@entrypoint decorator - tracing + self-registration + auto-dispatch."""

import inspect
import json
import logging
import os
import sys
from typing import Any, Callable, Dict, List, Optional

from ..decorator.tracker import track
from ..types import SpanType
from .agents_registry import AgentInfo, Param

LOGGER = logging.getLogger(__name__)

REGISTRY: Dict[str, Dict[str, Any]] = {}


def entrypoint(
    func: Optional[Callable] = None,
    *,
    name: Optional[str] = None,
    project: Optional[str] = None,
    type: SpanType = "general",
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

        params = extract_params(fn)

        docstring = inspect.getdoc(fn) or ""

        REGISTRY[agent_name] = {
            "func": tracked_fn,
            "name": agent_name,
            "project": agent_project,
            "python": sys.executable,
            "file": source_file,
            "params": params,
            "docstring": docstring,
        }

        self_register(agent_name, agent_project, source_file, params, docstring)

        # Auto-dispatch: if runner is calling us to execute this specific agent
        target_agent = os.environ.get("OPIK_AGENT")
        if target_agent == agent_name:
            dispatch_agent(tracked_fn)

        return tracked_fn

    if func is not None:
        return decorator(func)
    return decorator


def get_registry() -> Dict[str, Dict[str, Any]]:
    return REGISTRY


def extract_params(fn: Callable) -> List[Param]:
    """Extract parameter names and type annotations from a function."""
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


def self_register(
    agent_name: str,
    project: str,
    source_file: str,
    params: List[Any],
    docstring: str,
) -> None:
    """Write this agent's info to ~/.opik/agents.json."""
    from .agents_registry import register_agent

    try:
        register_agent(
            AgentInfo(
                name=agent_name,
                executable=sys.executable,
                source_file=source_file,
                project=project,
                params=params,
                description=docstring,
            )
        )
    except Exception:
        LOGGER.debug("Failed to self-register agent '%s'", agent_name, exc_info=True)


def dispatch_agent(func: Callable) -> None:
    """Execute the agent function with inputs from stdin, write result to file or stdout, exit."""
    try:
        raw = sys.stdin.read()
        inputs = json.loads(raw) if raw.strip() else {}
    except (json.JSONDecodeError, EOFError):
        inputs = {}

    exit_code = 0
    try:
        result = func(**inputs) if isinstance(inputs, dict) else func(inputs)
        if not isinstance(result, (dict, str, int, float, bool, list)):
            result = str(result)
        output = {"result": result}
    except Exception as e:
        output = {"error": f"{type(e).__name__}: {e}"}
        exit_code = 1

    result_file = os.environ.get("OPIK_RESULT_FILE")
    if result_file:
        with open(result_file, "w") as f:
            f.write(json.dumps(output))
    else:
        sys.stdout.write(json.dumps(output))
        sys.stdout.flush()
    sys.exit(exit_code)
