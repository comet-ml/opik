"""Happy-path e2e tests for the local runner.

Test 1 (basic): register echo agent, create job, verify trace output.
Test 2 (mask):  register echo_config agent, create mask, create job with
                mask_id, verify the mask value appears in the trace output.
"""

import os
import re
import shutil
import subprocess
import sys
import time
from typing import Optional

import pytest

from opik import Opik, synchronization
import opik.rest_api.client as rest_api_client


ECHO_APP = os.path.join(os.path.dirname(__file__), "echo_app.py")
ECHO_CONFIG_APP = os.path.join(os.path.dirname(__file__), "echo_config_app.py")
OPIK_CLI = shutil.which("opik") or "opik"

RUNNER_STARTUP_TIMEOUT = 15
JOB_COMPLETION_TIMEOUT = 30
TRACE_PROPAGATION_TIMEOUT = 30
AGENT_REGISTRATION_TIMEOUT = 10


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def register_agent(app_path: str) -> None:
    """Run an agent app once so it self-registers to ~/.opik/agents.json."""
    proc = subprocess.run(
        [sys.executable, app_path],
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert proc.returncode == 0, (
        f"Agent registration failed ({app_path}):\nstdout: {proc.stdout}\nstderr: {proc.stderr}"
    )


def submit_job(
    api: rest_api_client.OpikApi,
    agent_name: str,
    message: str,
    mask_id: Optional[str] = None,
) -> None:
    """Create a job for the given agent."""
    api.runners.create_job(
        agent_name=agent_name,
        inputs={"message": message},
        mask_id=mask_id,
    )


def wait_for_completed_job(
    api: rest_api_client.OpikApi, runner_id: str, match_text: str
):
    """Poll list_jobs until a completed job whose inputs contain *match_text* appears."""

    def _find():
        page = api.runners.list_jobs(runner_id=runner_id, size=20)
        if page.content:
            for j in page.content:
                if j.status == "completed" and j.inputs and match_text in str(j.inputs):
                    return j
        return None

    assert synchronization.until(
        lambda: _find() is not None,
        max_try_seconds=JOB_COMPLETION_TIMEOUT,
        allow_errors=True,
    ), f"No completed job with '{match_text}' found within {JOB_COMPLETION_TIMEOUT}s"

    return _find()


def find_trace_by_input(
    api: rest_api_client.OpikApi, project_name: str, match_text: str
):
    """Poll until a trace whose input contains *match_text* appears and has output."""

    def _find():
        page = api.traces.get_traces_by_project(
            project_name=project_name,
            size=20,
        )
        if page.content:
            for t in page.content:
                if t.input and match_text in str(t.input) and t.output:
                    return t
        return None

    assert synchronization.until(
        lambda: _find() is not None,
        max_try_seconds=TRACE_PROPAGATION_TIMEOUT,
        allow_errors=True,
    ), f"No trace with '{match_text}' found within {TRACE_PROPAGATION_TIMEOUT}s"

    return _find()


def wait_for_agent_registration(api: rest_api_client.OpikApi, agent_name: str) -> None:
    """Poll until the agent is registered with any runner in the workspace.

    Raises a pytest.fail if the agent is not registered within the timeout.
    """

    def _is_agent_registered():
        runners_page = api.runners.list_runners(size=50)
        if runners_page.content:
            for runner in runners_page.content:
                if runner.agents:
                    for agent in runner.agents:
                        if agent.name == agent_name:
                            return True
        return False

    if not synchronization.until(
        _is_agent_registered,
        max_try_seconds=AGENT_REGISTRATION_TIMEOUT,
        allow_errors=True,
    ):
        pytest.fail(
            f"Agent '{agent_name}' was not registered within {AGENT_REGISTRATION_TIMEOUT}s"
        )


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture()
def api_client():
    opik_client = Opik()
    yield opik_client.rest_client
    opik_client.end()


@pytest.fixture()
def subprocess_env():
    """Environment variables for subprocesses to reach the same Opik backend."""
    opik_client = Opik()
    try:
        cfg = opik_client.config
    finally:
        opik_client.end()

    env = os.environ.copy()
    env["OPIK_URL_OVERRIDE"] = cfg.url_override
    if cfg.api_key:
        env["OPIK_API_KEY"] = cfg.api_key
    if cfg.workspace:
        env["OPIK_WORKSPACE"] = cfg.workspace
    return env


@pytest.fixture()
def runner_process(api_client, subprocess_env):
    """Start ``opik connect --pair <code>`` and yield the runner_id."""
    pair = api_client.runners.generate_pairing_code()

    proc = subprocess.Popen(
        [OPIK_CLI, "connect", "--pair", pair.pairing_code],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        env=subprocess_env,
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
        pytest.fail(
            f"Runner did not start within {RUNNER_STARTUP_TIMEOUT}s.\n"
            f"Output:\n" + "\n".join(output_lines)
        )

    yield runner_id

    proc.terminate()
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


def test_runner_happy_path(api_client, runner_process):
    """Basic: register echo agent, run job, verify job result and trace output."""
    register_agent(ECHO_APP)
    message = f"hello-e2e-{int(time.time())}"

    wait_for_agent_registration(api_client, "echo")

    submit_job(api_client, "echo", message)

    job = wait_for_completed_job(api_client, runner_process, message)
    assert job.result is not None, "Completed job should have a result"
    assert f"echo: {message}" in str(job.result)

    trace = find_trace_by_input(api_client, "Default Project", message)
    assert f"echo: {message}" in str(trace.output)


def test_runner_with_mask(api_client, runner_process):
    """Mask: register echo_config agent, create mask, verify mask value in job result and trace."""
    register_agent(ECHO_CONFIG_APP)
    message = f"mask-e2e-{int(time.time())}"
    custom_greeting = f"custom-greeting-{int(time.time())}"

    wait_for_agent_registration(api_client, "echo_config")

    # Create a mask overriding the default greeting
    opik_client = Opik()
    try:
        agent_config = opik_client.get_agent_config()
        mask_id = agent_config.create_mask(
            parameters={"EchoConfig.greeting": custom_greeting},
        )
    finally:
        opik_client.end()

    submit_job(api_client, "echo_config", message, mask_id=mask_id)

    job = wait_for_completed_job(api_client, runner_process, message)
    assert job.result is not None, "Completed job should have a result"
    assert custom_greeting in str(job.result)

    trace = find_trace_by_input(api_client, "Default Project", message)
    assert custom_greeting in str(trace.output), (
        f"Expected '{custom_greeting}' in trace output, got: {trace.output}"
    )
