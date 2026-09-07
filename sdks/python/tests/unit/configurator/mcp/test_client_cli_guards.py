"""A client CLI that will not run must be reported, not raise.

`shutil.which` only checks the executable bit. `claude` and `codex` are Node
shims, so the common real break is node moving out from under them — the shim
still passes `which`, then raises FileNotFoundError at exec. That was an
unhandled traceback out of `opik configure`.
"""

import subprocess
from unittest import mock

import pytest

from opik.configurator.mcp import spec as mcp_spec
from opik.configurator.mcp import targets


def _spec():
    return mcp_spec.StdioServerSpec(
        command="uvx",
        args=["opik-mcp"],
        env=mcp_spec.mcp_env.McpServerEnv({"OPIK_API_KEY": "key"}),
    )


@pytest.fixture
def which_finds_everything(monkeypatch):
    monkeypatch.setattr(targets.shutil, "which", lambda name: f"/usr/bin/{name}")


@pytest.mark.parametrize(
    "install, client",
    [
        (targets._install_claude_code, "Claude Code"),
        (targets._install_codex, "Codex"),
    ],
)
class TestExecFailureIsReported:
    def test_shim_that_cannot_exec__returns_a_failure(
        self, install, client, which_finds_everything, monkeypatch
    ):
        """The errno names node, not the shim, so the message must explain."""

        def cannot_exec(*args, **kwargs):
            raise FileNotFoundError(2, "No such file or directory")

        monkeypatch.setattr(targets.subprocess, "run", cannot_exec)

        result = install(_spec())

        assert result.succeeded is False
        assert result.target_display_name == client
        assert "node" in result.detail, result.detail

    def test_cli_that_hangs__times_out_and_returns_a_failure(
        self, install, client, which_finds_everything, monkeypatch
    ):
        def hangs(*args, **kwargs):
            raise subprocess.TimeoutExpired(cmd="x", timeout=60)

        monkeypatch.setattr(targets.subprocess, "run", hangs)

        result = install(_spec())

        assert result.succeeded is False
        assert "did not finish" in result.detail


class TestTheCallsAreConstrained:
    def test_stdin_is_closed_and_a_timeout_is_set(
        self, which_finds_everything, monkeypatch
    ):
        """These inherit the terminal otherwise, so a CLI that prompts hangs."""
        run = mock.Mock(
            return_value=subprocess.CompletedProcess([], 0, stdout="", stderr="")
        )
        monkeypatch.setattr(targets.subprocess, "run", run)

        targets._install_claude_code(_spec())

        assert run.call_count >= 1
        for call in run.call_args_list:
            assert call.kwargs["stdin"] is subprocess.DEVNULL
            assert call.kwargs["timeout"] == targets.CLIENT_CLI_TIMEOUT_SECONDS
