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
) -> Dict[str, Any]:
    """Execute an agent by spawning its script as a subprocess.

    Sets OPIK_AGENT env var so @entrypoint auto-dispatches.
    When redis_client and job_id are provided, streams stdout/stderr
    to Redis for live log viewing.
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
