import contextlib
import pathlib
import subprocess
from unittest import mock

import pytest

from opik.configurator.mcp import install, spec, targets, verification
from opik.configurator.mcp import view as mcp_view


class RecordingView(mcp_view.LoggingInstallView):
    """Captures narration so tests assert on intent, not on log strings."""

    #: Set to script the host prompt; ``None`` uses the inherited numbered menu.
    host_choice = None

    def __init__(self):
        self.choose_calls = []
        self.plans = []
        self.plan_extras = []
        self.steps = []
        self.target_results = []
        self.verifications = []
        self.done_calls = []
        self.skips = []
        self.problems = []
        self.notes = []

    def plan(self, deployment, transport, targets, extras=()):
        self.plans.append((deployment, transport, list(targets)))
        self.plan_extras.append(list(extras))

    @contextlib.contextmanager
    def step(self, description):
        self.steps.append(description)
        yield

    def results(self, results):
        self.target_results.extend(results)

    def verification(self, succeeded, detail):
        self.verifications.append((succeeded, detail))

    def done(self, components, assistants):
        self.done_calls.append((list(components), list(assistants)))

    def skipped(self, message):
        self.skips.append(message)

    def problem(self, message):
        self.problems.append(message)

    def note(self, message):
        self.notes.append(message)

    def choose_hosts(self, title, candidates, preselected):
        self.choose_calls.append((title, list(candidates), list(preselected)))
        if self.host_choice is not None:
            return list(self.host_choice)
        return super().choose_hosts(title, candidates, preselected)

    @property
    def said(self) -> str:
        """Everything shown to the user, for substring assertions."""
        return " ".join(
            self.problems
            + self.skips
            + self.notes
            + [d for _, d in self.verifications]
            + [r.detail for r in self.target_results]
            + [f"{d} {t}" for d, t, _ in self.plans]
            + [loc for _, _, ts in self.plans for loc in (t.location for t in ts)]
        )


# Captured before the autouse fixtures below stub them out, so the tests that
# exercise these functions directly get the real implementation rather than the
# network-safety mock every other test relies on.
_REAL_VERIFY = install._verify
_REAL_WORKSPACE_AMBIGUITY = install._workspace_ambiguity


@pytest.fixture(autouse=True)
def interactive(monkeypatch):
    """Default to a terminal; the headless cases opt out explicitly.

    Pytest runs with stdin detached, so without this the new headless guard would
    silently rewrite what every prompt-driven test is exercising.
    """
    monkeypatch.setattr(install.interactive_helpers, "is_interactive", lambda: True)


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
        view=RecordingView(),
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

    install.setup_mcp_server(**(args := _make_args()))

    logged = args["view"].said
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

    install.setup_mcp_server(**(args := _make_args()))

    install_spy.assert_called_once()
    logged = args["view"].said
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

        install.setup_mcp_server(**(args := _make_args()), host_keys=["emacs"])

        install_spy.assert_not_called()
        assert "Known clients" in args["view"].said


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

        install.setup_mcp_server(**(args := _make_args()), assume_confirmed=True)

        verify.assert_called_once()
        view = args["view"]
        assert view.verifications == [(True, "connected to workspace ws")]
        assert view.done_calls == [(["MCP server"], ["Cursor"])]

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

        install.setup_mcp_server(**(args := _make_args()), assume_confirmed=True)

        view = args["view"]
        assert view.verifications[0][0] is False
        # A failed check must never be followed by "restart, it works".
        assert view.done_calls == []

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

        install.setup_mcp_server(**(args := _make_args()), assume_confirmed=True)

        install_spy.assert_not_called()
        warned = args["view"].said
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

        install.setup_mcp_server(**(args := _make_args()))

        warned = args["view"].said
        assert "astral.sh/uv/install" in warned


def test_setup_mcp_server__no_host__manual_instructions_mention_the_host_flag(
    monkeypatch,
):
    monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
    monkeypatch.setattr(
        targets, "HOST_TARGETS", [_target("cursor", False, mock.Mock())]
    )

    install.setup_mcp_server(**(args := _make_args()))

    logged = args["view"].said
    assert "--ai-client" in logged


class TestPlanLabels:
    """The plan block answers "which Opik, over what, into which files?"."""

    def test_deployment_label__cloud_names_the_workspace(self):
        assert install._deployment_label(False, False, "acme-ai") == (
            "Opik Cloud · workspace acme-ai"
        )

    def test_deployment_label__self_hosted_comet(self):
        assert "Self-hosted Comet" in install._deployment_label(False, True, "acme-ai")

    def test_deployment_label__local_needs_no_workspace(self):
        assert install._deployment_label(True, False, None) == "Local Opik"

    def test_transport_label__hosted_mentions_browser_sign_in(self):
        label = install._transport_label(spec.RemoteServerSpec(url="https://x/v1/mcp"))
        assert "browser sign-in" in label

    def test_transport_label__local_mentions_where_credentials_go(self):
        label = install._transport_label(
            spec.StdioServerSpec(command="uvx", args=["opik-mcp"], env={})
        )
        assert "uvx" in label and "host config" in label

    def test_target_location__claude_code_with_cli__names_the_command(
        self, monkeypatch
    ):
        """Saying `~/.claude.json` would be wrong when we shell out to the CLI."""
        monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/claude")
        target = targets.find_target("claude-code")

        location = install._target_location(target, mock.Mock())

        assert location == "via `claude mcp add`"

    def test_target_location__claude_code_without_cli__names_the_file(
        self, monkeypatch
    ):
        monkeypatch.setattr(install.shutil, "which", lambda name: None)
        target = targets.find_target("claude-code")

        assert install._target_location(target, mock.Mock()).endswith(".claude.json")

    def test_target_location__codex__names_the_command(self):
        """Codex config is TOML; we drive its CLI rather than editing the file."""
        target = targets.find_target("codex")

        assert install._target_location(target, mock.Mock()) == "via `codex mcp add`"

    def test_target_location__file_hosts__collapse_home(self, monkeypatch, tmp_path):
        monkeypatch.setattr(pathlib.Path, "home", classmethod(lambda cls: tmp_path))
        target = targets.find_target("cursor")

        assert install._target_location(target, mock.Mock()).startswith("~/")

    def test_setup_mcp_server__plan_is_shown_before_anything_is_written(
        self, monkeypatch
    ):
        """Consent needs visibility: the plan must precede the write, not follow it."""
        order = []
        monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")

        def record_install(server_spec):
            order.append("write")
            return targets.InstallResult("Cursor", True, "Added", "Added")

        monkeypatch.setattr(
            targets, "HOST_TARGETS", [_target("cursor", True, record_install)]
        )
        args = _make_args()
        view = args["view"]
        original_plan = view.plan

        def record_plan(*a, **k):
            order.append("plan")
            return original_plan(*a, **k)

        view.plan = record_plan

        install.setup_mcp_server(**args, assume_confirmed=True)

        assert order == ["plan", "write"]


class TestCandidateAndConfirm:
    def test_candidate_targets__explicit_keys__ignore_detection(self, monkeypatch):
        monkeypatch.setattr(
            targets, "HOST_TARGETS", [_target("codex", False, mock.Mock())]
        )

        assert [t.key for t in install._candidate_targets(["codex"])] == ["codex"]

    def test_candidate_targets__unknown_key__is_dropped(self, monkeypatch):
        monkeypatch.setattr(
            targets, "HOST_TARGETS", [_target("codex", True, mock.Mock())]
        )

        assert install._candidate_targets(["emacs"]) == []

    def test_candidate_targets__no_keys__uses_detection(self, monkeypatch):
        monkeypatch.setattr(
            targets,
            "HOST_TARGETS",
            [
                _target("codex", True, mock.Mock()),
                _target("cursor", False, mock.Mock()),
            ],
        )

        assert [t.key for t in install._candidate_targets(None)] == ["codex"]

    def test_confirm_targets__explicit_keys__do_not_prompt(self, monkeypatch):
        monkeypatch.setattr(
            "builtins.input", mock.Mock(side_effect=AssertionError("must not prompt"))
        )
        candidates = [_target("codex", True, mock.Mock())]

        assert (
            install._confirm_targets(candidates, ["codex"], False, RecordingView())
            == candidates
        )

    def test_confirm_targets__assume_confirmed__does_not_prompt(self, monkeypatch):
        monkeypatch.setattr(
            "builtins.input", mock.Mock(side_effect=AssertionError("must not prompt"))
        )
        candidates = [_target("codex", True, mock.Mock())]

        assert (
            install._confirm_targets(candidates, None, True, RecordingView())
            == candidates
        )

    def test_confirm_targets__interactive__asks(self, monkeypatch):
        candidates = [_target("codex", True, mock.Mock())]
        view = RecordingView()
        view.host_choice = []

        assert install._confirm_targets(candidates, None, False, view) == []
        assert view.choose_calls


class TestTerminalRequired:
    """Outside a session, an explicit request is what authorises the write.

    Registering the server edits configuration files owned by other tools, so an
    unflagged run does nothing. But a coding agent asked to set Opik up has no tty
    and a live instruction, and naming a client — or passing `--install-mcp`, which
    arrives as `assume_confirmed` — is how it says so.
    """

    def test_setup_mcp_server__no_terminal__installs_nothing(self, monkeypatch):
        monkeypatch.setattr(
            install.interactive_helpers, "is_interactive", lambda: False
        )
        monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
        monkeypatch.setattr(
            "builtins.input", mock.Mock(side_effect=AssertionError("must not prompt"))
        )
        install_spy = mock.Mock()
        monkeypatch.setattr(
            install.mcp_targets,
            "detected_targets",
            lambda: [_target("cursor", True, install_spy)],
        )
        view = RecordingView()

        result = install.setup_mcp_server(
            api_key="k",
            workspace="ws",
            base_url="https://x/opik",
            api_url="https://x/opik/api",
            use_local=False,
            self_hosted_comet=False,
            view=view,
        )

        assert result == []
        install_spy.assert_not_called()
        assert view.skips, "the user is told why nothing happened"

    def test_setup_mcp_server__no_terminal_with_client__installs(self, monkeypatch):
        """The agent path: no tty, but a named client says what was asked for."""
        monkeypatch.setattr(
            install.interactive_helpers, "is_interactive", lambda: False
        )
        monkeypatch.setattr(install.shutil, "which", lambda name: "/usr/bin/uvx")
        install_spy = mock.Mock()
        monkeypatch.setattr(
            install.mcp_targets,
            "find_target",
            lambda key: _target("cursor", True, install_spy),
        )

        result = install.setup_mcp_server(
            api_key="k",
            workspace="ws",
            base_url="https://x/opik",
            api_url="https://x/opik/api",
            use_local=False,
            self_hosted_comet=False,
            host_keys=["cursor"],
            view=RecordingView(),
        )

        assert result == ["cursor"]
        install_spy.assert_called_once()

    def test_confirm_targets__terminal__still_asks(self, monkeypatch):
        monkeypatch.setattr(install.interactive_helpers, "is_interactive", lambda: True)
        candidates = [_target("cursor", True, mock.Mock())]
        view = RecordingView()
        view.host_choice = []

        assert install._confirm_targets(candidates, None, False, view) == []
        assert view.choose_calls
