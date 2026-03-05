import json
import os
import sys
from typing import Any, Callable

from .. import id_helpers


def should_dispatch(agent_name: str) -> bool:
    """Check if this process was spawned by the runner to execute an agent."""
    return os.environ.get("OPIK_AGENT") == agent_name


def run_dispatch(func: Callable[..., Any]) -> Any:
    """Execute the agent function with inputs from stdin, writing results to OPIK_RESULT_FILE.

    Injects distributed trace headers when OPIK_TRACE_ID is set so the
    @track decorator on the function links back to the runner job's trace.
    """
    inputs = json.loads(sys.stdin.read())

    trace_id = os.environ.get("OPIK_TRACE_ID")
    if trace_id:
        span_id = id_helpers.generate_id()
        inputs["opik_distributed_trace_headers"] = {
            "opik_trace_id": trace_id,
            "opik_parent_span_id": span_id,
        }

    result = func(**inputs)

    result_file = os.environ.get("OPIK_RESULT_FILE")
    if result_file:
        with open(result_file, "w") as f:
            json.dump({"result": result}, f)

    return result
