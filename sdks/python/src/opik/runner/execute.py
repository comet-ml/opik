"""Execute agent functions via subprocess with optional log streaming."""

import json
import logging
import os
import subprocess
import tempfile
import threading
from typing import Any, Dict, Optional

LOGGER = logging.getLogger(__name__)


def _stream_pipe(
    pipe: Any,
    stream_name: str,
    redis_client: Any,
    job_id: str,
) -> None:
    log_key = f"opik:job:{job_id}:logs"
    for line in pipe:
        try:
            entry = json.dumps({"stream": stream_name, "text": line.rstrip("\n")})
            redis_client.rpush(log_key, entry)
        except Exception:
            LOGGER.warning("Failed to stream log line to Redis", exc_info=True)


def execute_agent(
    agent_name: str,
    python: str,
    file: str,
    inputs: Dict[str, Any],
    timeout: int = 300,
    redis_client: Optional[Any] = None,
    job_id: str = "",
    graph_file: Optional[str] = None,
    disable_tracing: bool = False,
) -> Dict[str, Any]:
    """Execute an agent by spawning its script as a subprocess.

    Sets OPIK_AGENT env var so @entrypoint auto-dispatches.
    When redis_client and job_id are provided, streams stdout/stderr
    to Redis for live log viewing.
    When graph_file is provided, activates graph capture and writes
    the call graph to that path.
    """
    streaming = redis_client is not None and job_id

    result_file = None
    try:
        if streaming:
            fd, result_file = tempfile.mkstemp(suffix=".json", prefix="opik_result_")
            os.close(fd)

        env = {**os.environ, "OPIK_AGENT": agent_name, "PYTHONUNBUFFERED": "1"}
        if result_file:
            env["OPIK_RESULT_FILE"] = result_file
        if graph_file:
            env["OPIK_DEBUG_GRAPH_FILE"] = graph_file
        if disable_tracing:
            env["OPIK_TRACK_DISABLE"] = "true"

        inputs_json = json.dumps(inputs)
        LOGGER.info("Executing agent '%s': %s %s", agent_name, python, file)

        if streaming:
            return _execute_streaming(
                python, file, inputs_json, env, timeout,
                redis_client, job_id, result_file,  # type: ignore[arg-type]
            )
        else:
            return _execute_simple(python, file, inputs_json, env, timeout, agent_name)
    finally:
        if result_file and os.path.exists(result_file):
            os.unlink(result_file)


def _execute_simple(
    python: str,
    file: str,
    inputs_json: str,
    env: Dict[str, str],
    timeout: int,
    agent_name: str,
) -> Dict[str, Any]:
    try:
        proc = subprocess.run(
            [python, file],
            input=inputs_json,
            capture_output=True,
            text=True,
            timeout=timeout,
            env=env,
        )
    except subprocess.TimeoutExpired:
        return {"error": f"Agent '{agent_name}' timed out after {timeout}s"}
    except FileNotFoundError:
        return {"error": f"Python not found: {python}"}

    if proc.returncode != 0:
        stderr = proc.stderr.strip()
        stdout = proc.stdout.strip()
        LOGGER.error("Agent '%s' exited with code %d: %s", agent_name, proc.returncode, stderr)
        result: Dict[str, Any] = {"error": stderr or f"Agent exited with code {proc.returncode}"}
        if stdout:
            result["stdout"] = stdout
        return result

    stdout = proc.stdout.strip()
    if not stdout:
        return {"error": "Agent produced no output"}

    try:
        return json.loads(stdout)
    except json.JSONDecodeError:
        return {"error": f"Agent produced invalid JSON: {stdout}"}


def _execute_streaming(
    python: str,
    file: str,
    inputs_json: str,
    env: Dict[str, str],
    timeout: int,
    redis_client: Any,
    job_id: str,
    result_file: str,
) -> Dict[str, Any]:
    try:
        proc = subprocess.Popen(
            [python, file],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=env,
        )
    except FileNotFoundError:
        return {"error": f"Python not found: {python}"}

    stdout_thread = threading.Thread(
        target=_stream_pipe,
        args=(proc.stdout, "stdout", redis_client, job_id),
        daemon=True,
    )
    stderr_thread = threading.Thread(
        target=_stream_pipe,
        args=(proc.stderr, "stderr", redis_client, job_id),
        daemon=True,
    )
    stdout_thread.start()
    stderr_thread.start()

    try:
        proc.stdin.write(inputs_json)  # type: ignore[union-attr]
        proc.stdin.close()  # type: ignore[union-attr]
        proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()
        return {"error": f"Agent timed out after {timeout}s"}

    stdout_thread.join(timeout=5)
    stderr_thread.join(timeout=5)

    if not os.path.exists(result_file) or os.path.getsize(result_file) == 0:
        if proc.returncode != 0:
            return {"error": f"Agent exited with code {proc.returncode}"}
        return {"error": "Agent produced no output"}

    try:
        with open(result_file, "r") as f:
            return json.loads(f.read())
    except (json.JSONDecodeError, OSError) as e:
        return {"error": f"Failed to read agent result: {e}"}


def execute_debug_function(
    python: str,
    file: str,
    function_name: str,
    inputs: Dict[str, Any],
    trace_id: str,
    parent_span_id: Optional[str] = None,
    project_name: str = "default",
    timeout: int = 60,
) -> Dict[str, Any]:
    """Execute a single @track-decorated function for debug stepping.

    Uses a bootstrap script that imports the agent module (not as __main__)
    to avoid triggering `if __name__ == "__main__"` blocks, then calls
    just the target function with trace context set up.
    """
    fd, result_file = tempfile.mkstemp(suffix=".json", prefix="opik_debug_")
    os.close(fd)

    try:
        bootstrap = _build_debug_bootstrap(file, function_name, result_file)

        env = {
            **os.environ,
            "OPIK_DEBUG_TRACE_ID": trace_id,
            "OPIK_DEBUG_PROJECT": project_name,
            "OPIK_DEBUG_INPUTS": json.dumps(inputs),
            "PYTHONUNBUFFERED": "1",
        }
        if parent_span_id:
            env["OPIK_DEBUG_PARENT_SPAN_ID"] = parent_span_id
        env["OPIK_TRACK_DISABLE"] = "true"
        # Prevent @entrypoint from auto-dispatching during import
        env.pop("OPIK_AGENT", None)
        env.pop("OPIK_DEBUG_FUNC", None)

        LOGGER.info("Debug executing '%s' from %s", function_name, file)

        try:
            proc = subprocess.run(
                [python, "-c", bootstrap],
                input="",
                capture_output=True,
                text=True,
                timeout=timeout,
                env=env,
                cwd=os.path.dirname(file),
            )
        except subprocess.TimeoutExpired:
            return {"error": f"Debug function '{function_name}' timed out after {timeout}s"}
        except FileNotFoundError:
            return {"error": f"Python not found: {python}"}

        if proc.returncode != 0:
            stderr = proc.stderr.strip()
            return {"error": stderr or f"Debug function exited with code {proc.returncode}"}

        if not os.path.exists(result_file) or os.path.getsize(result_file) == 0:
            stdout = proc.stdout.strip()
            if stdout:
                try:
                    return json.loads(stdout)
                except json.JSONDecodeError:
                    return {"error": f"Invalid output: {stdout}"}
            return {"error": "Debug function produced no output"}

        with open(result_file, "r") as f:
            return json.loads(f.read())
    finally:
        if os.path.exists(result_file):
            os.unlink(result_file)


def _build_debug_bootstrap(file: str, function_name: str, result_file: str) -> str:
    """Build a Python script that imports the agent module and calls one function."""
    return f"""
import importlib.util, json, os, sys
sys.path.insert(0, os.path.dirname({file!r}))
spec = importlib.util.spec_from_file_location("_debug_agent", {file!r})
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
func = getattr(mod, {function_name!r}, None)
if func is None:
    with open({result_file!r}, "w") as f:
        json.dump({{"error": "Function '{function_name}' not found in module"}}, f)
    sys.exit(0)
from opik import context_storage
from opik.api_objects.span import SpanData
from opik.api_objects.trace import TraceData
trace_id = os.environ.get("OPIK_DEBUG_TRACE_ID", "")
parent_span_id = os.environ.get("OPIK_DEBUG_PARENT_SPAN_ID")
project_name = os.environ.get("OPIK_DEBUG_PROJECT", "default")
if trace_id:
    context_storage.set_trace_data(TraceData(id=trace_id, name="debug", project_name=project_name))
if parent_span_id and trace_id:
    context_storage.add_span_data(SpanData(id=parent_span_id, trace_id=trace_id, name="debug-parent", project_name=project_name))
inputs = json.loads(os.environ.get("OPIK_DEBUG_INPUTS", "{{}}"))
try:
    result = func(**inputs)
    if not isinstance(result, (dict, str, int, float, bool, list)):
        result = str(result)
    output = {{"result": result}}
except Exception as e:
    output = {{"error": f"{{type(e).__name__}}: {{e}}"}}
with open({result_file!r}, "w") as f:
    json.dump(output, f)
import opik
opik.flush_tracker()
"""
