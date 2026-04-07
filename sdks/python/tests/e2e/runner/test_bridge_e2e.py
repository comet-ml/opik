"""Happy-path e2e tests for the bridge command system.

Test 1 (exec): submit a simple Exec command, verify stdout and exit code.
Test 2 (nonzero exit): verify non-zero exit codes are returned.
Test 3 (exec background): submit a background Exec, verify PID returned.
"""

import time

from .conftest import RunnerInfo


def _submit_and_wait(api_client, runner_id, cmd_type, args):
    resp = api_client.runners.submit_bridge_command(
        runner_id,
        type=cmd_type,
        args=args,
    )
    return api_client.runners.get_bridge_command(
        runner_id,
        resp.command_id,
        wait=True,
        timeout=15,
    )


def test_bridge_exec_echo(api_client, runner_process: RunnerInfo):
    """Submit a simple echo command and verify the result."""
    marker = f"bridge-e2e-{int(time.time())}"

    cmd = _submit_and_wait(
        api_client,
        runner_process.runner_id,
        "Exec",
        {"command": f"echo {marker}"},
    )

    assert cmd.status == "completed"
    assert marker in cmd.result["stdout"]
    assert cmd.result["exit_code"] == 0


def test_bridge_exec_nonzero_exit(api_client, runner_process: RunnerInfo):
    """Verify non-zero exit codes are returned correctly."""
    cmd = _submit_and_wait(
        api_client,
        runner_process.runner_id,
        "Exec",
        {"command": "exit 42"},
    )

    assert cmd.status == "completed"
    assert cmd.result["exit_code"] == 42


def test_bridge_exec_background(api_client, runner_process: RunnerInfo):
    """Submit a background command, verify PID is returned."""
    cmd = _submit_and_wait(
        api_client,
        runner_process.runner_id,
        "Exec",
        {"command": "sleep 30", "background": True},
    )

    assert cmd.status == "completed"
    assert "pid" in cmd.result
    assert cmd.result["status"] == "running"
