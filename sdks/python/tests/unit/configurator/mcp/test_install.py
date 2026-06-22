import subprocess
from unittest import mock

import pytest

from opik.configurator.mcp import install, targets


@pytest.fixture(autouse=True)
def prefetch_run(monkeypatch):
    """Stub the pre-fetch subprocess so tests never shell out to uv."""
    run_mock = mock.Mock(
        return_value=subprocess.CompletedProcess([], 0, stdout="", stderr="")
    )
    monkeypatch.setattr(install.subprocess, "run", run_mock)
    return run_mock


def _make_args(**overrides):
    args = dict(
        api_key="some-key",
        workspace="ws",
        base_url="https://www.comet.com/",
        api_url="https://www.comet.com/opik/api/",
        use_local=False,
        self_hosted_comet=False,
    )
    args.update(overrides)
    return args


def _target(key, detected, install_fn):
    return targets.HostTarget(
        key=key,
        display_name=key,
        is_detected=lambda: detected,
        install=install_fn,
    )


def test_setup_mcp_server__uvx_missing__does_not_install(monkeypatch):
    monkeypatch.setattr(install.shutil, "which", lambda name: None)
    install_spy = mock.Mock()
    monkeypatch.setattr(
        targets, "HOST_TARGETS", [_target("claude-code", True, install_spy)]
    )

    install.setup_mcp_server(**_make_args())

    install_spy.assert_not_called()


def test_setup_mcp_server__no_host_detected__does_not_install(monkeypatch):
    monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
    install_spy = mock.Mock()
    monkeypatch.setattr(
        targets, "HOST_TARGETS", [_target("cursor", False, install_spy)]
    )

    install.setup_mcp_server(**_make_args())

    install_spy.assert_not_called()


def test_setup_mcp_server__no_host__manual_config_redacts_api_key(monkeypatch):
    monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
    monkeypatch.setattr(
        targets, "HOST_TARGETS", [_target("cursor", False, mock.Mock())]
    )
    logger_spy = mock.Mock()
    monkeypatch.setattr(install, "LOGGER", logger_spy)

    install.setup_mcp_server(**_make_args())

    logged = " ".join(str(call) for call in logger_spy.info.call_args_list)
    assert "some-key" not in logged
    assert "***REDACTED***" in logged


def test_setup_mcp_server__single_host_selected__installs(monkeypatch):
    monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
    install_spy = mock.Mock(return_value=targets.InstallResult("Cursor", True, "Added"))
    monkeypatch.setattr(targets, "HOST_TARGETS", [_target("cursor", True, install_spy)])
    monkeypatch.setattr("builtins.input", lambda message: "y")  # confirm the host

    install.setup_mcp_server(**_make_args())

    install_spy.assert_called_once()
    spec = install_spy.call_args.args[0]
    assert spec.command == "/usr/bin/uvx"
    assert spec.args == ["opik-mcp"]
    assert spec.env["OPIK_API_KEY"] == "some-key"


def test_setup_mcp_server__menu_lists_detected_hosts(monkeypatch):
    monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
    monkeypatch.setattr(
        targets,
        "HOST_TARGETS",
        [
            _target("Claude Code", True, mock.Mock()),
            _target("Cursor", True, mock.Mock()),
            _target("VS Code Copilot", False, mock.Mock()),
        ],
    )
    prompts = []

    def fake_input(message):
        prompts.append(message)
        return "4"  # Skip (2 hosts -> 1,2 hosts, 3 all, 4 skip)

    monkeypatch.setattr("builtins.input", fake_input)

    install.setup_mcp_server(**_make_args())

    assert "Claude Code" in prompts[0]
    assert "Cursor" in prompts[0]
    assert "All of the above" in prompts[0]
    assert "VS Code Copilot" not in prompts[0]


def test_setup_mcp_server__select_all__installs_every_detected_host(monkeypatch):
    monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
    claude_spy = mock.Mock(return_value=targets.InstallResult("Claude", True, "Added"))
    cursor_spy = mock.Mock(return_value=targets.InstallResult("Cursor", True, "Added"))
    monkeypatch.setattr(
        targets,
        "HOST_TARGETS",
        [_target("Claude Code", True, claude_spy), _target("Cursor", True, cursor_spy)],
    )
    monkeypatch.setattr("builtins.input", lambda message: "3")  # All of the above

    install.setup_mcp_server(**_make_args())

    claude_spy.assert_called_once()
    cursor_spy.assert_called_once()


def test_setup_mcp_server__comma_separated_selection__installs_each(monkeypatch):
    monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
    claude_spy = mock.Mock(return_value=targets.InstallResult("Claude", True, "Added"))
    cursor_spy = mock.Mock(return_value=targets.InstallResult("Cursor", True, "Added"))
    vscode_spy = mock.Mock(return_value=targets.InstallResult("VS Code", True, "Added"))
    monkeypatch.setattr(
        targets,
        "HOST_TARGETS",
        [
            _target("Claude Code", True, claude_spy),
            _target("Cursor", True, cursor_spy),
            _target("VS Code Copilot", True, vscode_spy),
        ],
    )
    monkeypatch.setattr("builtins.input", lambda message: "1,3")  # Claude + VS Code

    install.setup_mcp_server(**_make_args())

    claude_spy.assert_called_once()
    vscode_spy.assert_called_once()
    cursor_spy.assert_not_called()


def test_setup_mcp_server__invalid_menu_choice_then_valid__retries(monkeypatch):
    monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
    claude_spy = mock.Mock(return_value=targets.InstallResult("Claude", True, "Added"))
    cursor_spy = mock.Mock(return_value=targets.InstallResult("Cursor", True, "Added"))
    monkeypatch.setattr(
        targets,
        "HOST_TARGETS",
        [_target("Claude Code", True, claude_spy), _target("Cursor", True, cursor_spy)],
    )
    # invalid (non-digit), out-of-range, then a valid single choice
    monkeypatch.setattr("builtins.input", mock.Mock(side_effect=["x", "99", "2"]))

    install.setup_mcp_server(**_make_args())

    cursor_spy.assert_called_once()
    claude_spy.assert_not_called()


def test_setup_mcp_server__select_subset__installs_only_chosen(monkeypatch):
    monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
    claude_spy = mock.Mock(return_value=targets.InstallResult("Claude", True, "Added"))
    cursor_spy = mock.Mock(return_value=targets.InstallResult("Cursor", True, "Added"))
    monkeypatch.setattr(
        targets,
        "HOST_TARGETS",
        [_target("Claude Code", True, claude_spy), _target("Cursor", True, cursor_spy)],
    )
    monkeypatch.setattr("builtins.input", lambda message: "2")  # only Cursor

    install.setup_mcp_server(**_make_args())

    claude_spy.assert_not_called()
    cursor_spy.assert_called_once()


def test_setup_mcp_server__user_skips__does_not_install(monkeypatch):
    monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
    install_spy = mock.Mock()
    monkeypatch.setattr(targets, "HOST_TARGETS", [_target("cursor", True, install_spy)])
    monkeypatch.setattr("builtins.input", lambda message: "n")  # decline

    install.setup_mcp_server(**_make_args())

    install_spy.assert_not_called()


def test_setup_mcp_server__prefetches_opik_mcp_before_install(
    monkeypatch, prefetch_run
):
    monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
    install_spy = mock.Mock(return_value=targets.InstallResult("Cursor", True, "Added"))
    monkeypatch.setattr(targets, "HOST_TARGETS", [_target("cursor", True, install_spy)])
    monkeypatch.setattr("builtins.input", lambda message: "y")

    install.setup_mcp_server(**_make_args())

    commands = [call.args[0] for call in prefetch_run.call_args_list]
    assert any(cmd[1:] == ["tool", "install", "opik-mcp"] for cmd in commands)
    install_spy.assert_called_once()


def test_setup_mcp_server__prefetch_failure__is_non_fatal(monkeypatch, prefetch_run):
    prefetch_run.return_value = subprocess.CompletedProcess(
        [], 1, stdout="", stderr="network unreachable"
    )
    monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
    install_spy = mock.Mock(return_value=targets.InstallResult("Cursor", True, "Added"))
    monkeypatch.setattr(targets, "HOST_TARGETS", [_target("cursor", True, install_spy)])
    monkeypatch.setattr("builtins.input", lambda message: "y")

    install.setup_mcp_server(**_make_args())

    install_spy.assert_called_once()


def test_setup_mcp_server__prefetch_raises_oserror__is_non_fatal(
    monkeypatch, prefetch_run
):
    prefetch_run.side_effect = OSError("uv vanished mid-flight")
    monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
    install_spy = mock.Mock(return_value=targets.InstallResult("Cursor", True, "Added"))
    monkeypatch.setattr(targets, "HOST_TARGETS", [_target("cursor", True, install_spy)])
    monkeypatch.setattr("builtins.input", lambda message: "y")

    install.setup_mcp_server(**_make_args())  # must not raise

    install_spy.assert_called_once()


def test_setup_mcp_server__skip__does_not_prefetch(monkeypatch, prefetch_run):
    monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
    monkeypatch.setattr(targets, "HOST_TARGETS", [_target("cursor", True, mock.Mock())])
    monkeypatch.setattr("builtins.input", lambda message: "n")  # decline

    install.setup_mcp_server(**_make_args())

    prefetch_run.assert_not_called()


def test_setup_mcp_server__install_failure__is_reported(monkeypatch):
    monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
    install_spy = mock.Mock(
        return_value=targets.InstallResult("Cursor", False, "could not write config")
    )
    monkeypatch.setattr(targets, "HOST_TARGETS", [_target("cursor", True, install_spy)])
    monkeypatch.setattr("builtins.input", lambda message: "y")  # confirm the host
    logger_spy = mock.Mock()
    monkeypatch.setattr(install, "LOGGER", logger_spy)

    install.setup_mcp_server(**_make_args())

    install_spy.assert_called_once()
    logged = " ".join(str(call) for call in logger_spy.warning.call_args_list)
    assert "could not write config" in logged
