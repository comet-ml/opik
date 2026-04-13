"""Happy-path e2e tests for the bridge command system.

Exec tests: echo, nonzero exit, background process.
File operation flow: write → list → search → edit → read, all via the API.
"""

import time

from opik.rest_api.errors.conflict_error import ConflictError
from opik.runner.bridge_auth import sign

from .conftest import RunnerInfo

_BRIDGE_READY_TIMEOUT = 15


def _submit_and_wait(api_client, runner_id, cmd_type, args, bridge_key=b""):
    if bridge_key:
        args = dict(args)
        args["_hmac"] = sign(bridge_key, cmd_type, args)

    deadline = time.monotonic() + _BRIDGE_READY_TIMEOUT
    while True:
        try:
            resp = api_client.runners.create_bridge_command(
                runner_id,
                type=cmd_type,
                args=args,
            )
            break
        except ConflictError:
            if time.monotonic() > deadline:
                raise
            time.sleep(1)

    return api_client.runners.get_bridge_command(
        runner_id,
        resp.command_id,
        wait=True,
        timeout=15,
    )


# ---------------------------------------------------------------------------
# Auth tests
# ---------------------------------------------------------------------------


def test_bridge_command__unsigned__fails_with_auth_failed(
    api_client, bridge_runner_process: RunnerInfo
):
    cmd = _submit_and_wait(
        api_client,
        bridge_runner_process.runner_id,
        "Exec",
        {"command": "echo should-not-run"},
    )
    assert cmd.status == "failed"
    assert cmd.error["code"] == "auth_failed"


def test_bridge_command__invalid_hmac__fails_with_auth_failed(
    api_client, bridge_runner_process: RunnerInfo
):
    cmd = _submit_and_wait(
        api_client,
        bridge_runner_process.runner_id,
        "Exec",
        {"command": "echo should-not-run", "_hmac": "dGhpcyBpcyBub3QgYSB2YWxpZCBobWFj"},
    )
    assert cmd.status == "failed"
    assert cmd.error["code"] == "auth_failed"


# ---------------------------------------------------------------------------
# Exec tests
# ---------------------------------------------------------------------------


def test_bridge_exec_echo(api_client, bridge_runner_process: RunnerInfo):
    """Submit a simple echo command and verify the result."""
    marker = f"bridge-e2e-{int(time.time())}"

    cmd = _submit_and_wait(
        api_client,
        bridge_runner_process.runner_id,
        "Exec",
        {"command": f"echo {marker}"},
        bridge_key=bridge_runner_process.bridge_key,
    )

    assert cmd.status == "completed"
    assert marker in cmd.result["stdout"]
    assert cmd.result["exit_code"] == 0


def test_bridge_exec_nonzero_exit(api_client, bridge_runner_process: RunnerInfo):
    """Verify non-zero exit codes are returned correctly."""
    cmd = _submit_and_wait(
        api_client,
        bridge_runner_process.runner_id,
        "Exec",
        {"command": "exit 42"},
        bridge_key=bridge_runner_process.bridge_key,
    )

    assert cmd.status == "completed"
    assert cmd.result["exit_code"] == 42


def test_bridge_exec_background(api_client, bridge_runner_process: RunnerInfo):
    """Submit a background command, verify PID is returned."""
    cmd = _submit_and_wait(
        api_client,
        bridge_runner_process.runner_id,
        "Exec",
        {"command": "sleep 30", "background": True},
        bridge_key=bridge_runner_process.bridge_key,
    )

    assert cmd.status == "completed"
    assert "pid" in cmd.result
    assert cmd.result["status"] == "running"


# ---------------------------------------------------------------------------
# File operation flow: write → list → search → edit → read
# ---------------------------------------------------------------------------


def test_bridge_file_operations(api_client, bridge_runner_process: RunnerInfo):
    """Write a file, find it with list/search, edit it, and read back."""
    rid = bridge_runner_process.runner_id
    bk = bridge_runner_process.bridge_key
    marker = f"xyzzy_{int(time.time())}"
    filename = f"bridge_e2e_{int(time.time())}.py"
    original_content = f"# {marker}\n"

    # 1. Write
    cmd = _submit_and_wait(
        api_client,
        rid,
        "WriteFile",
        {"path": filename, "content": original_content},
        bridge_key=bk,
    )
    assert cmd.status == "completed"
    assert cmd.result["created"] is True

    # 2. ListFiles — new file should appear
    cmd = _submit_and_wait(
        api_client,
        rid,
        "ListFiles",
        {"pattern": f"**/{filename}"},
        bridge_key=bk,
    )
    assert cmd.status == "completed"
    assert any(filename in f for f in cmd.result["files"]), (
        f"{filename} not in {cmd.result['files']}"
    )

    # 3. EditFile — replace content
    cmd = _submit_and_wait(
        api_client,
        rid,
        "EditFile",
        {
            "path": filename,
            "edits": [{"old_string": marker, "new_string": f"edited_{marker}"}],
        },
        bridge_key=bk,
    )
    assert cmd.status == "completed"
    assert cmd.result["edits_applied"] == 1

    # 4. ReadFile — verify the edit took effect
    cmd = _submit_and_wait(
        api_client,
        rid,
        "ReadFile",
        {"path": filename},
        bridge_key=bk,
    )
    assert cmd.status == "completed"
    assert f"edited_{marker}" in cmd.result["content"]

    # 5. Cleanup via Exec
    _submit_and_wait(
        api_client,
        rid,
        "Exec",
        {"command": f"rm {filename}"},
        bridge_key=bk,
    )
