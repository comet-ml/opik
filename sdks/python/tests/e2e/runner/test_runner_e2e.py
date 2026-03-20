"""Happy-path e2e tests for the local runner.

Test 1 (basic): register echo agent, create job, verify trace output.
Test 2 (mask):  register echo_config agent, create mask, create job with
                mask_id, verify the mask value appears in the trace output.
"""

import time
from typing import Optional


import opik
import opik.rest_api.client as rest_api_client
from opik.api_objects.agent_config.config import AgentConfigManager
from ..conftest import OPIK_E2E_TESTS_PROJECT_NAME
from .conftest import RunnerInfo


JOB_COMPLETION_TIMEOUT = 30
TRACE_PROPAGATION_TIMEOUT = 30
AGENT_REGISTRATION_TIMEOUT = 10


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def submit_job(
    api: rest_api_client.OpikApi,
    agent_name: str,
    message: str,
    project_id: str,
    mask_id: Optional[str] = None,
) -> None:
    """Create a job for the given agent."""
    api.runners.create_job(
        agent_name=agent_name,
        inputs={"message": message},
        project_id=project_id,
        mask_id=mask_id,
    )


def wait_for_completed_job(
    api: rest_api_client.OpikApi, runner_id: str, match_text: str
):
    """Poll list_jobs until a completed job whose inputs contain *match_text* appears."""
    result = []

    def _find():
        page = api.runners.list_jobs(runner_id=runner_id, size=20)
        if page.content:
            for j in page.content:
                if j.status == "completed" and j.inputs and match_text in str(j.inputs):
                    result.clear()
                    result.append(j)
                    return True
        return False

    assert opik.synchronization.until(
        _find,
        max_try_seconds=JOB_COMPLETION_TIMEOUT,
        allow_errors=True,
    ), f"No completed job with '{match_text}' found within {JOB_COMPLETION_TIMEOUT}s"

    return result[0]


def find_trace_by_input(
    api: rest_api_client.OpikApi, project_name: str, match_text: str
):
    """Poll until a trace whose input contains *match_text* appears and has output."""
    result = []

    def _find():
        page = api.traces.get_traces_by_project(
            project_name=project_name,
            size=20,
        )
        if page.content:
            for t in page.content:
                if t.input and match_text in str(t.input) and t.output:
                    result.clear()
                    result.append(t)
                    return True
        return False

    assert opik.synchronization.until(
        _find,
        max_try_seconds=TRACE_PROPAGATION_TIMEOUT,
        allow_errors=True,
    ), f"No trace with '{match_text}' found within {TRACE_PROPAGATION_TIMEOUT}s"

    return result[0]


def wait_for_agent_registration(
    api: rest_api_client.OpikApi, agent_name: str, project_id: str
) -> None:
    """Poll until the agent is registered with any runner in the project."""

    def _is_agent_registered():
        runners_page = api.runners.list_runners(project_id=project_id, size=50)
        if runners_page.content:
            for runner in runners_page.content:
                if runner.agents:
                    for agent in runner.agents:
                        if agent.name == agent_name:
                            return True
        return False

    if not opik.synchronization.until(
        _is_agent_registered,
        max_try_seconds=AGENT_REGISTRATION_TIMEOUT,
        allow_errors=True,
    ):
        raise AssertionError(
            f"Agent '{agent_name}' was not registered within {AGENT_REGISTRATION_TIMEOUT}s"
        )


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


def test_runner_happy_path(api_client, runner_process: RunnerInfo, project_id):
    """Basic: register echo agent, run job, verify job result and trace output."""
    message = f"hello-e2e-{int(time.time())}"

    wait_for_agent_registration(api_client, "echo", project_id)

    submit_job(api_client, "echo", message, project_id)

    job = wait_for_completed_job(api_client, runner_process.runner_id, message)
    assert job.result is not None, "Completed job should have a result"
    assert f"echo: {message}" in str(job.result)

    trace = find_trace_by_input(api_client, OPIK_E2E_TESTS_PROJECT_NAME, message)
    assert f"echo: {message}" in str(trace.output)


def test_runner_with_mask(
    opik_client, api_client, runner_process: RunnerInfo, project_id
):
    """Mask: register echo_config agent, create mask, verify mask value in job result and trace."""
    message = f"mask-e2e-{int(time.time())}"
    custom_greeting = f"custom-greeting-{int(time.time())}"

    wait_for_agent_registration(api_client, "echo_config", project_id)

    manager = AgentConfigManager(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        rest_client_=opik_client.rest_client,
    )
    mask_id = manager.create_mask(
        parameters={"EchoConfig.greeting": custom_greeting},
    )

    submit_job(api_client, "echo_config", message, project_id, mask_id=mask_id)

    job = wait_for_completed_job(api_client, runner_process.runner_id, message)
    assert job.result is not None, "Completed job should have a result"
    assert custom_greeting in str(job.result)

    trace = find_trace_by_input(api_client, OPIK_E2E_TESTS_PROJECT_NAME, message)
    assert custom_greeting in str(trace.output), (
        f"Expected '{custom_greeting}' in trace output, got: {trace.output}"
    )
