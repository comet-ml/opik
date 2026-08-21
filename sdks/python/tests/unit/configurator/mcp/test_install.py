import pathlib
import subprocess
from unittest import mock

import pytest

from opik.configurator.mcp import install, spec, targets, verification

# Captured before the autouse fixtures below stub them out, so the tests that
# exercise these functions directly get the real implementation rather than the
# network-safety mock every other test relies on.
_REAL_VERIFY = install._verify
_REAL_WORKSPACE_AMBIGUITY = install._workspace_ambiguity


@pytest.fixture(autouse=True)
def prefetch_run(monkeypatch):
    """Stub the pre-fetch subprocess so tests never shell out to uv."""
    run_mock = mock.Mock(
        return_value=subprocess.CompletedProcess([], 0, stdout="", stderr="")
    )
    monkeypatch.setattr(install.subprocess, "run", run_mock)
    return run_mock


@pytest.fixture(autouse=True)
def verify(monkeypatch):
    """Stub post-install verification so tests never reach the network."""
    verify_mock = mock.Mock(
        return_value=verification.VerificationResult(True, "connected to workspace ws")
    )
    monkeypatch.setattr(install, "_verify", verify_mock)
    return verify_mock


@pytest.fixture(autouse=True)
def unambiguous_workspace(monkeypatch):
    """Default every test to a workspace that needs no disambiguation."""
    monkeypatch.setattr(install, "_workspace_ambiguity", lambda **kwargs: None)


@pytest.fixture(autouse=True)
def no_hosted_mcp(monkeypatch):
    """Default every test to the uvx fallback: no hosted MCP server detected.

    Tests that exercise the remote path override this within the test body.
    """
    monkeypatch.setattr(
        install.mcp_detection, "detect_hosted_mcp_server", lambda **kwargs: None
    )


def _make_args(**overrides):
    args = dict(
        api_key="some-key",
        workspace="ws",
        base_url="https://www.comet.com/",
        api_url="https://www.comet.com/opik/api/",
        use_local=False,
        self_hosted_comet=False,
        check_tls_certificate=True,
        force_local_server=False,
    )
    args.update(overrides)
    return args


def _target(key, detected, install_fn):
    return targets.HostTarget(
        key=key,
        display_name=key,
        config_path=lambda: pathlib.Path("/dev/null"),
        top_level_key="mcpServers",
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


def test_setup_mcp_server__hosted_detected__installs_remote_spec(monkeypatch):
    monkeypatch.setattr(
        install.mcp_detection,
        "detect_hosted_mcp_server",
        lambda **kwargs: "https://dev.comet.com/opik/api/v1/mcp",
    )
    install_spy = mock.Mock(return_value=targets.InstallResult("Cursor", True, "Added"))
    monkeypatch.setattr(targets, "HOST_TARGETS", [_target("cursor", True, install_spy)])
    monkeypatch.setattr("builtins.input", lambda message: "y")

    install.setup_mcp_server(**_make_args())

    install_spy.assert_called_once()
    server_spec = install_spy.call_args.args[0]
    assert isinstance(server_spec, spec.RemoteServerSpec)
    assert server_spec.url == "https://dev.comet.com/opik/api/v1/mcp"


def test_setup_mcp_server__hosted_detected__does_not_prefetch(
    monkeypatch, prefetch_run
):
    monkeypatch.setattr(
        install.mcp_detection,
        "detect_hosted_mcp_server",
        lambda **kwargs: "https://dev.comet.com/opik/api/v1/mcp",
    )
    install_spy = mock.Mock(return_value=targets.InstallResult("Cursor", True, "Added"))
    monkeypatch.setattr(targets, "HOST_TARGETS", [_target("cursor", True, install_spy)])
    monkeypatch.setattr("builtins.input", lambda message: "y")

    install.setup_mcp_server(**_make_args())

    prefetch_run.assert_not_called()
    install_spy.assert_called_once()


def test_setup_mcp_server__force_local__skips_probe_and_installs_uvx(monkeypatch):
    monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
    detect_spy = mock.Mock(return_value="https://dev.comet.com/opik/api/v1/mcp")
    monkeypatch.setattr(install.mcp_detection, "detect_hosted_mcp_server", detect_spy)
    install_spy = mock.Mock(return_value=targets.InstallResult("Cursor", True, "Added"))
    monkeypatch.setattr(targets, "HOST_TARGETS", [_target("cursor", True, install_spy)])
    monkeypatch.setattr("builtins.input", lambda message: "y")

    install.setup_mcp_server(**_make_args(force_local_server=True))

    detect_spy.assert_not_called()
    install_spy.assert_called_once()
    server_spec = install_spy.call_args.args[0]
    assert isinstance(server_spec, spec.StdioServerSpec)


def test_setup_mcp_server__hosted_detected__no_uvx_still_installs(monkeypatch):
    """The remote path has no `uvx` prerequisite, unlike the local fallback."""
    monkeypatch.setattr(install.shutil, "which", lambda name: None)
    monkeypatch.setattr(
        install.mcp_detection,
        "detect_hosted_mcp_server",
        lambda **kwargs: "https://dev.comet.com/opik/api/v1/mcp",
    )
    install_spy = mock.Mock(return_value=targets.InstallResult("Cursor", True, "Added"))
    monkeypatch.setattr(targets, "HOST_TARGETS", [_target("cursor", True, install_spy)])
    monkeypatch.setattr("builtins.input", lambda message: "y")

    install.setup_mcp_server(**_make_args())

    install_spy.assert_called_once()


class TestExplicitHosts:
    """`--host` is what makes the command usable from CI, Docker, or an agent."""

    def test_setup_mcp_server__host_keys__installs_without_detection_or_prompt(
        self, monkeypatch
    ):
        monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
        install_spy = mock.Mock(
            return_value=targets.InstallResult("Codex", True, "Added")
        )
        # Undetected on purpose: naming a host is the caller stating a fact, and a
        # fresh CI image or Dockerfile will not have the host installed yet.
        monkeypatch.setattr(
            targets, "HOST_TARGETS", [_target("codex", False, install_spy)]
        )
        monkeypatch.setattr(
            "builtins.input", mock.Mock(side_effect=AssertionError("must not prompt"))
        )

        install.setup_mcp_server(**_make_args(), host_keys=["codex"])

        install_spy.assert_called_once()

    def test_setup_mcp_server__several_host_keys__installs_each(self, monkeypatch):
        monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
        codex_spy = mock.Mock(
            return_value=targets.InstallResult("Codex", True, "Added")
        )
        cursor_spy = mock.Mock(
            return_value=targets.InstallResult("Cursor", True, "Added")
        )
        monkeypatch.setattr(
            targets,
            "HOST_TARGETS",
            [_target("codex", False, codex_spy), _target("cursor", False, cursor_spy)],
        )

        install.setup_mcp_server(**_make_args(), host_keys=["codex", "cursor"])

        codex_spy.assert_called_once()
        cursor_spy.assert_called_once()

    def test_setup_mcp_server__unknown_host_key__installs_nothing(self, monkeypatch):
        monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
        install_spy = mock.Mock()
        monkeypatch.setattr(
            targets, "HOST_TARGETS", [_target("cursor", True, install_spy)]
        )
        logger_spy = mock.Mock()
        monkeypatch.setattr(install, "LOGGER", logger_spy)

        install.setup_mcp_server(**_make_args(), host_keys=["emacs"])

        install_spy.assert_not_called()
        logged = " ".join(str(call) for call in logger_spy.warning.call_args_list)
        assert "Unknown AI host" in logged


class TestAssumeConfirmed:
    def test_setup_mcp_server__assume_confirmed__installs_detected_without_asking(
        self, monkeypatch
    ):
        """The caller already showed a prompt naming these hosts; don't ask twice."""
        monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
        install_spy = mock.Mock(
            return_value=targets.InstallResult("Cursor", True, "Added")
        )
        monkeypatch.setattr(
            targets, "HOST_TARGETS", [_target("cursor", True, install_spy)]
        )
        monkeypatch.setattr(
            "builtins.input", mock.Mock(side_effect=AssertionError("must not prompt"))
        )

        install.setup_mcp_server(**_make_args(), assume_confirmed=True)

        install_spy.assert_called_once()

    def test_setup_mcp_server__assume_confirmed__still_skips_when_nothing_detected(
        self, monkeypatch
    ):
        monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
        install_spy = mock.Mock()
        monkeypatch.setattr(
            targets, "HOST_TARGETS", [_target("cursor", False, install_spy)]
        )

        install.setup_mcp_server(**_make_args(), assume_confirmed=True)

        install_spy.assert_not_called()


class TestVerification:
    def test_setup_mcp_server__install_succeeds__verifies_and_reports(
        self, monkeypatch, verify
    ):
        monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
        monkeypatch.setattr(
            targets,
            "HOST_TARGETS",
            [
                _target(
                    "cursor",
                    True,
                    mock.Mock(
                        return_value=targets.InstallResult("Cursor", True, "Added")
                    ),
                )
            ],
        )
        logger_spy = mock.Mock()
        monkeypatch.setattr(install, "LOGGER", logger_spy)

        install.setup_mcp_server(**_make_args(), assume_confirmed=True)

        verify.assert_called_once()
        logged = " ".join(str(call) for call in logger_spy.info.call_args_list)
        assert "Verified" in logged
        assert "connected to workspace ws" in logged

    def test_setup_mcp_server__verification_fails__warns_instead_of_claiming_success(
        self, monkeypatch, verify
    ):
        verify.return_value = verification.VerificationResult(
            False, "Opik rejected the credentials written to your host config"
        )
        monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
        monkeypatch.setattr(
            targets,
            "HOST_TARGETS",
            [
                _target(
                    "cursor",
                    True,
                    mock.Mock(
                        return_value=targets.InstallResult("Cursor", True, "Added")
                    ),
                )
            ],
        )
        logger_spy = mock.Mock()
        monkeypatch.setattr(install, "LOGGER", logger_spy)

        install.setup_mcp_server(**_make_args(), assume_confirmed=True)

        warned = " ".join(str(call) for call in logger_spy.warning.call_args_list)
        assert "verification failed" in warned
        # The old "restart your host, it works" line must not appear.
        info = " ".join(str(call) for call in logger_spy.info.call_args_list)
        assert "Restart your AI host" not in info

    def test_setup_mcp_server__every_host_failed__does_not_verify(
        self, monkeypatch, verify
    ):
        monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
        monkeypatch.setattr(
            targets,
            "HOST_TARGETS",
            [
                _target(
                    "cursor",
                    True,
                    mock.Mock(
                        return_value=targets.InstallResult("Cursor", False, "nope")
                    ),
                )
            ],
        )

        install.setup_mcp_server(**_make_args(), assume_confirmed=True)

        verify.assert_not_called()

    def test_verify__remote_spec__probes_the_hosted_endpoint(self, monkeypatch):
        hosted_spy = mock.Mock(
            return_value=verification.VerificationResult(True, "reachable")
        )
        monkeypatch.setattr(verification, "verify_hosted_endpoint", hosted_spy)

        _REAL_VERIFY(
            server_spec=spec.RemoteServerSpec(url="https://c.example/opik/api/v1/mcp"),
            api_key="key",
            workspace="ws",
            api_url="https://c.example/opik/api/",
            check_tls_certificate=True,
        )

        assert hosted_spy.call_args.kwargs["mcp_url"] == (
            "https://c.example/opik/api/v1/mcp"
        )

    def test_verify__stdio_spec__exercises_the_credentials(self, monkeypatch):
        local_spy = mock.Mock(
            return_value=verification.VerificationResult(True, "connected")
        )
        monkeypatch.setattr(verification, "verify_local_credentials", local_spy)

        _REAL_VERIFY(
            server_spec=spec.StdioServerSpec(
                command="/usr/bin/uvx", args=["opik-mcp"], env={}
            ),
            api_key="key",
            workspace="ws",
            api_url="https://c.example/opik/api/",
            check_tls_certificate=True,
        )

        assert local_spy.call_args.kwargs["api_key"] == "key"
        assert local_spy.call_args.kwargs["workspace"] == "ws"


class TestWorkspaceAmbiguity:
    def _args(self, **overrides):
        args = dict(
            api_key="key",
            workspace="default",
            base_url="https://www.comet.com/",
            use_local=False,
            check_tls_certificate=True,
        )
        args.update(overrides)
        return args

    def test_workspace_ambiguity__default_workspace_many_available__refuses(
        self, monkeypatch
    ):
        monkeypatch.setattr(
            install.mcp_verification, "list_workspaces", lambda **k: ["acme", "beta"]
        )

        message = _REAL_WORKSPACE_AMBIGUITY(**self._args())

        assert message is not None
        assert "acme" in message and "beta" in message
        assert "opik configure" in message

    def test_workspace_ambiguity__named_workspace__is_fine(self, monkeypatch):
        list_spy = mock.Mock()
        monkeypatch.setattr(install.mcp_verification, "list_workspaces", list_spy)

        assert _REAL_WORKSPACE_AMBIGUITY(**self._args(workspace="acme")) is None
        list_spy.assert_not_called()

    def test_workspace_ambiguity__single_workspace__is_fine(self, monkeypatch):
        monkeypatch.setattr(
            install.mcp_verification, "list_workspaces", lambda **k: ["acme"]
        )
        assert _REAL_WORKSPACE_AMBIGUITY(**self._args()) is None

    def test_workspace_ambiguity__lookup_failed__does_not_block(self, monkeypatch):
        """Block on positive evidence of ambiguity only, never on a failed lookup."""
        monkeypatch.setattr(
            install.mcp_verification, "list_workspaces", lambda **k: None
        )
        assert _REAL_WORKSPACE_AMBIGUITY(**self._args()) is None

    def test_workspace_ambiguity__local_deployment__is_fine(self, monkeypatch):
        list_spy = mock.Mock()
        monkeypatch.setattr(install.mcp_verification, "list_workspaces", list_spy)

        assert _REAL_WORKSPACE_AMBIGUITY(**self._args(use_local=True)) is None
        list_spy.assert_not_called()

    def test_workspace_ambiguity__no_api_key__is_fine(self, monkeypatch):
        list_spy = mock.Mock()
        monkeypatch.setattr(install.mcp_verification, "list_workspaces", list_spy)

        assert _REAL_WORKSPACE_AMBIGUITY(**self._args(api_key=None)) is None
        list_spy.assert_not_called()

    def test_setup_mcp_server__ambiguous_workspace__installs_nothing(self, monkeypatch):
        monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
        monkeypatch.setattr(
            install, "_workspace_ambiguity", lambda **k: "pick a workspace first"
        )
        install_spy = mock.Mock()
        monkeypatch.setattr(
            targets, "HOST_TARGETS", [_target("cursor", True, install_spy)]
        )
        logger_spy = mock.Mock()
        monkeypatch.setattr(install, "LOGGER", logger_spy)

        install.setup_mcp_server(**_make_args(), assume_confirmed=True)

        install_spy.assert_not_called()
        warned = " ".join(str(call) for call in logger_spy.warning.call_args_list)
        assert "pick a workspace first" in warned


class TestUvHint:
    def test_uv_install_hint__names_the_command_to_run(self, monkeypatch):
        monkeypatch.setattr(install.sys, "platform", "darwin")
        hint = install._uv_install_hint()
        assert "curl -LsSf https://astral.sh/uv/install.sh | sh" in hint

    def test_uv_install_hint__windows__uses_powershell(self, monkeypatch):
        monkeypatch.setattr(install.sys, "platform", "win32")
        hint = install._uv_install_hint()
        assert "powershell" in hint
        assert "install.ps1" in hint

    def test_setup_mcp_server__uvx_missing__logs_the_install_command(self, monkeypatch):
        monkeypatch.setattr(install.shutil, "which", lambda name: None)
        monkeypatch.setattr(
            targets, "HOST_TARGETS", [_target("cursor", True, mock.Mock())]
        )
        logger_spy = mock.Mock()
        monkeypatch.setattr(install, "LOGGER", logger_spy)

        install.setup_mcp_server(**_make_args())

        warned = " ".join(str(call) for call in logger_spy.warning.call_args_list)
        assert "astral.sh/uv/install" in warned


def test_setup_mcp_server__no_host__manual_instructions_mention_the_host_flag(
    monkeypatch,
):
    monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
    monkeypatch.setattr(
        targets, "HOST_TARGETS", [_target("cursor", False, mock.Mock())]
    )
    logger_spy = mock.Mock()
    monkeypatch.setattr(install, "LOGGER", logger_spy)

    install.setup_mcp_server(**_make_args())

    logged = " ".join(str(call) for call in logger_spy.info.call_args_list)
    assert "--host" in logged
