"""Happy-path e2e test for the local runner.

Flow:
1. Generate a pairing code via the REST API.
2. Start ``opik connect --pair <code>`` in a subprocess (runner process).
3. Wait for the runner to appear (extract runner_id from its stdout).
4. Run echo_app.py in a subprocess so that the agent self-registers.
5. Create a job targeting the "echo" agent.
6. Poll the job until it completes.
7. Verify a trace was created with the expected output.
"""

import os
import re
import shutil
import subprocess
import sys
import time

import pytest

from opik import synchronization
from opik.rest_api.client import OpikApi


ECHO_APP = os.path.join(os.path.dirname(__file__), "echo_app.py")
OPIK_CLI = shutil.which("opik") or "opik"

RUNNER_STARTUP_TIMEOUT = 15
JOB_COMPLETION_TIMEOUT = 30
TRACE_PROPAGATION_TIMEOUT = 15


@pytest.fixture()
def api_client():
    client = OpikApi()
    yield client


@pytest.fixture()
def runner_process(api_client):
    """Start ``opik connect --pair <code>`` and yield the runner_id.

    Tears down the runner subprocess on exit.
    """
    pair = api_client.runners.generate_pairing_code()
    pairing_code = pair.pairing_code

    proc = subprocess.Popen(
        [OPIK_CLI, "connect", "--pair", pairing_code],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )

    runner_id = None
    deadline = time.monotonic() + RUNNER_STARTUP_TIMEOUT
    output_lines = []

    while time.monotonic() < deadline:
        line = proc.stdout.readline()
        if not line:
            if proc.poll() is not None:
                break
            time.sleep(0.1)
            continue

        output_lines.append(line.rstrip())

        match = re.search(r"Runner connected \(ID: ([^)]+)\)", line)
        if match:
            runner_id = match.group(1)
            break

    if runner_id is None:
        proc.terminate()
        proc.wait(timeout=5)
        collected = "\n".join(output_lines)
        pytest.fail(
            f"Runner did not start within {RUNNER_STARTUP_TIMEOUT}s.\n"
            f"Output:\n{collected}"
        )

    yield runner_id

    proc.terminate()
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()


@pytest.fixture()
def register_echo_agent():
    """Run echo_app.py once so that the agent self-registers to ~/.opik/agents.json."""
    proc = subprocess.run(
        [sys.executable, ECHO_APP],
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert proc.returncode == 0, (
        f"echo_app.py registration failed:\nstdout: {proc.stdout}\nstderr: {proc.stderr}"
    )


def test_runner_happy_path(api_client, register_echo_agent, runner_process):
    """Run a job through the full runner pipeline and verify the trace."""
    runner_id = runner_process
    message = f"hello-e2e-{int(time.time())}"

    # Wait for the runner to pick up the registered agent via heartbeat
    time.sleep(2)

    # Create a job (raises on non-2xx)
    api_client.runners.create_job(
        agent_name="echo",
        inputs={"message": message},
    )

    # Find the job by listing jobs for this runner
    def _find_job():
        page = api_client.runners.list_jobs(runner_id, size=10)
        if page.content:
            for j in page.content:
                if j.agent_name == "echo" and j.inputs and j.inputs.get("message") == message:
                    return j
        return None

    assert synchronization.until(
        lambda: _find_job() is not None,
        max_try_seconds=10,
        allow_errors=True,
    ), "Job not found in runner's job list"

    job = _find_job()
    job_id = job.id

    # Wait for the job to complete
    def _job_completed():
        j = api_client.runners.get_job(job_id)
        return j.status in ("completed", "failed")

    assert synchronization.until(
        _job_completed,
        max_try_seconds=JOB_COMPLETION_TIMEOUT,
        allow_errors=True,
    ), f"Job {job_id} did not complete within {JOB_COMPLETION_TIMEOUT}s"

    completed_job = api_client.runners.get_job(job_id)
    assert completed_job.status == "completed", (
        f"Job failed: {completed_job.error}"
    )

    assert completed_job.trace_id is not None, "Job completed but no trace_id"

    # Search for a trace matching our input in the default project.
    # The trace is created by the agent subprocess's @track decorator with its
    # own trace_id, which may differ from the runner-assigned trace_id on the job.
    def _find_trace():
        page = api_client.traces.get_traces_by_project(
            project_name="Default Project",
            size=20,
        )
        if page.content:
            for t in page.content:
                if t.input and message in str(t.input):
                    return t
        return None

    assert synchronization.until(
        lambda: _find_trace() is not None,
        max_try_seconds=TRACE_PROPAGATION_TIMEOUT,
        allow_errors=True,
    ), f"No trace with message '{message}' found within {TRACE_PROPAGATION_TIMEOUT}s"

    trace = _find_trace()
    assert trace.output is not None
    assert f"echo: {message}" in str(trace.output)
