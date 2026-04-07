"""Happy-path e2e tests for the bridge command system.

Exec tests: echo, nonzero exit, background process.
File operation flow: write → list → search → edit → read, all via the API.
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


# ---------------------------------------------------------------------------
# Exec tests
# ---------------------------------------------------------------------------


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


# ---------------------------------------------------------------------------
# File operation flow: write → list → search → edit → read
# ---------------------------------------------------------------------------


def test_bridge_file_operations(api_client, runner_process: RunnerInfo):
    """Write a file, find it with list/search, edit it, and read back."""
    rid = runner_process.runner_id
    filename = f"bridge_e2e_{int(time.time())}.txt"
    original_content = "hello from e2e test\n"

    # 1. Write
    cmd = _submit_and_wait(
        api_client,
        rid,
        "WriteFile",
        {"path": filename, "content": original_content},
    )
    assert cmd.status == "completed"
    assert cmd.result["created"] is True

    # 2. ListFiles — new file should appear
    cmd = _submit_and_wait(
        api_client,
        rid,
        "ListFiles",
        {"pattern": f"**/{filename}"},
    )
    assert cmd.status == "completed"
    found = [e["path"] for e in cmd.result["entries"] if not e.get("is_dir")]
    assert any(filename in p for p in found), f"{filename} not in {found}"

    # 3. SearchFiles — search for content inside the file
    cmd = _submit_and_wait(
        api_client,
        rid,
        "SearchFiles",
        {"pattern": "hello from e2e"},
    )
    assert cmd.status == "completed"
    match_files = [m["path"] for m in cmd.result["matches"]]
    assert any(filename in p for p in match_files), (
        f"{filename} not in search results: {match_files}"
    )

    # 4. EditFile — replace content
    cmd = _submit_and_wait(
        api_client,
        rid,
        "EditFile",
        {
            "path": filename,
            "edits": [{"old_string": "hello", "new_string": "goodbye"}],
        },
    )
    assert cmd.status == "completed"
    assert cmd.result["applied"] == 1

    # 5. ReadFile — verify the edit took effect
    cmd = _submit_and_wait(
        api_client,
        rid,
        "ReadFile",
        {"path": filename},
    )
    assert cmd.status == "completed"
    assert "goodbye from e2e test" in cmd.result["content"]

    # 6. Cleanup via Exec
    _submit_and_wait(
        api_client,
        rid,
        "Exec",
        {"command": f"rm {filename}"},
    )
