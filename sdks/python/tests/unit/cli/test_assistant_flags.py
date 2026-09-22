"""What `opik configure`'s flags actually cause to be written.

These assert at the boundary that matters: which *installers ran*. The existing
CLI tests mock `assistants.setup` wholesale, so they cannot see that it registered
an MCP server — which is how `--no-install-mcp` shipped registering one anyway.
"""

import pathlib
from types import SimpleNamespace
from unittest import mock

import pytest
from click.testing import CliRunner

from opik.cli import cli
from opik.cli import assistants as cli_assistants
from opik.cli import configure as configure_cli
from opik.configurator import mcp as mcp_installer
from opik.configurator import skills as skills_installer
from opik.configurator.mcp import install as mcp_install
from opik.configurator.skills import install as skills_install

MCP = "setup_mcp_server"
SKILLS = "setup_skills"


PARAMS = {
    "api_key": "key",
    "workspace": "default",
    "base_url": "https://www.comet.com/",
    "api_url": "https://www.comet.com/opik/api",
    "use_local": False,
    "self_hosted_comet": False,
    "check_tls_certificate": True,
}


@pytest.fixture
def ran(monkeypatch):
    """Run `opik configure` with both installers stubbed, recording which ran.

    The configurator itself is replaced by a stub that calls the injected
    assistant step the same way the real one does (configure.py:111-125), so the
    flag -> consent -> installer path is exercised for real without needing a
    live Opik to verify credentials against.
    """
    calls: list = []

    # `_deployment_type()` reads these when there is no terminal to ask in.
    monkeypatch.setenv("OPIK_API_KEY", "key")
    monkeypatch.setenv("OPIK_WORKSPACE", "default")
    monkeypatch.setenv("OPIK_URL_OVERRIDE", "https://www.comet.com/opik/api")

    def fake_mcp(**kwargs):
        calls.append(MCP)
        return mcp_install.InstallReport(registered=("cursor",), verified=True)

    def fake_skills(host_keys, *args, **kwargs):
        calls.append(SKILLS)
        return skills_install.InstallResult(succeeded=True, skills=["opik"])

    def fake_configurator(**kwargs):
        return mock.Mock(
            configure=lambda: kwargs["assistant_setup"](
                PARAMS,
                kwargs["install_mcp"],
                kwargs["install_skills"],
                kwargs["automatic_approvals"],
            )
        )

    def run(*flags, interactive=False):
        calls.clear()
        with (
            mock.patch.object(mcp_installer, "setup_mcp_server", fake_mcp),
            mock.patch.object(skills_installer, "setup_skills", fake_skills),
            mock.patch.object(
                skills_installer, "detected_host_keys", return_value=["cursor"]
            ),
            mock.patch.object(
                mcp_installer, "detected_host_keys", return_value=["Cursor"]
            ),
            mock.patch.object(
                configure_cli.opik_configure, "OpikConfigurator", fake_configurator
            ),
            mock.patch.object(
                configure_cli.interactive_helpers,
                "is_interactive",
                return_value=interactive,
            ),
            # With a terminal the deployment picker prompts; the stub configurator
            # ignores which one was chosen, so any answer will do.
            mock.patch.object(
                configure_cli.interactive_helpers,
                "ask_user_for_deployment_type",
                return_value=configure_cli.interactive_helpers.DeploymentType.CLOUD,
            ),
            mock.patch.object(
                cli_assistants.install_view, "render_skill_pack", return_value=True
            ),
        ):
            result = CliRunner().invoke(cli, ["configure", *flags])
        assert result.exit_code == 0, result.output
        run.output = result.output
        return list(calls)

    return run


class TestOptOutIsHonoured:
    """`--no-install-mcp` must not write an MCP server registration.

    It did: the skills-only path routed through a `setup()` whose first act was
    always registering the server, and forced `skills_flag=True` on the way, so
    the pack was installed with no prompt either.
    """

    def test_no_install_mcp__registers_nothing(self, ran):
        assert ran("--no-install-mcp") == []

    def test_no_install_mcp_with_skills__installs_only_the_pack(self, ran):
        assert ran("--no-install-mcp", "--install-skills") == [SKILLS]

    def test_no_install_mcp__does_not_force_the_pack(self, ran):
        """`--no-install-mcp` alone is not a request to install the pack."""
        assert SKILLS not in ran("--no-install-mcp")

    def test_both_declined__registers_nothing(self, ran):
        assert ran("--no-install-mcp", "--no-install-skills") == []

    def test_no_install_skills__still_registers_the_server(self, ran):
        assert ran("--install-mcp", "--no-install-skills") == [MCP]


class TestExplicitRequestsRunWithoutATerminal:
    """A named flag is the request, so it works where there is nobody to ask."""

    def test_install_mcp__registers(self, ran):
        assert ran("--install-mcp") == [MCP]

    def test_both_flags__do_both(self, ran):
        assert ran("--install-mcp", "--install-skills") == [MCP, SKILLS]

    def test_install_skills_alone__installs_the_pack(self, ran):
        """The pack does not require the server; detection supplies the targets."""
        assert ran("--install-skills") == [SKILLS]


class TestUnflaggedRunsNeverWrite:
    def test_no_flags_no_terminal__writes_nothing(self, ran):
        assert ran() == []

    def test_yes_alone__writes_nothing(self, ran):
        """`-y` answers Opik's questions; it is not consent to edit other tools."""
        assert ran("-y") == []

    def test_yes_with_a_terminal__writes_nothing(self, ran):
        assert ran("-y", interactive=True) == []


class TestSkipIsExplainedHonestly:
    def test_unattended_skip__does_not_blame_minus_y(self, ran):
        """The command passes `-y` down whenever there is no tty.

        Inferring the reason at print time therefore told people who never typed
        the flag that the flag was why their editor was skipped.
        """
        assert ran() == []

        assert "no terminal to ask in" in ran.output
        assert "-y answers" not in ran.output

    def test_minus_y_skip__says_so(self, ran):
        assert ran("-y", interactive=True) == []

        assert "-y answers" in ran.output


class TestThePickerIsReallyExercised:
    """End-to-end through the real installer and picker, not a fabricated Outcome.

    Every other test here stubs `assistants.setup` or `setup_mcp_server`, so a
    broken picker or a lost `InstallReport.declined` would pass unnoticed —
    which is exactly how select-all came to resolve to "my client is not
    listed" and install nothing.
    """

    @staticmethod
    def _pick(keys, detected=("claude-code", "cursor")):
        from opik.cli import assistants, selector
        from opik.configurator.mcp import targets as mcp_targets
        from opik.configurator.mcp import install as mcp_install
        from opik.configurator import consent

        installed = []

        def target(key):
            return mcp_targets.HostTarget(
                key=key,
                display_name=key,
                config_path=lambda: pathlib.Path("/dev/null"),
                top_level_key="mcpServers",
                is_detected=lambda: True,
                install=lambda spec: (
                    installed.append(key),
                    mcp_targets.InstallResult(
                        target_display_name=key, succeeded=True, detail="ok"
                    ),
                )[1],
            )

        pressed = iter(keys)
        with (
            mock.patch.object(mcp_install.shutil, "which", lambda n: "/usr/bin/uvx"),
            mock.patch.object(
                mcp_install.interactive_helpers, "is_interactive", return_value=True
            ),
            mock.patch.object(
                mcp_install.mcp_targets,
                "detected_targets",
                lambda: [target(k) for k in detected],
            ),
            mock.patch.object(mcp_install, "_workspace_ambiguity", lambda **k: None),
            mock.patch.object(
                mcp_install,
                "_verify",
                lambda **k: SimpleNamespace(succeeded=True, detail="verified"),
            ),
            mock.patch.object(
                mcp_install.mcp_detection, "detect_hosted_mcp_server", lambda **k: None
            ),
            mock.patch.object(selector, "is_supported", return_value=True),
            mock.patch.object(
                selector, "_key_reader", return_value=lambda: next(pressed)
            ),
            mock.patch.object(
                assistants.skills_installer,
                "setup_skills",
                return_value=skills_install.InstallResult(
                    succeeded=True, skills=["opik"]
                ),
            ),
            mock.patch.object(
                assistants.skills_installer,
                "detected_host_keys",
                return_value=list(detected),
            ),
            mock.patch.object(assistants.click, "confirm", return_value=False),
        ):
            outcome = assistants.setup(
                PARAMS,
                install_mcp=True,
                skills=consent.Verdict(consent.Decision.SKIP, consent.Reason.DECLINED),
            )
        return outcome, installed

    def test_select_all__registers_every_client(self):
        """`a` must not resolve to a synthetic row and install nothing."""
        from opik.cli import selector

        outcome, installed = self._pick([selector.TOGGLE_ALL, selector.ACCEPT])

        assert sorted(installed) == ["claude-code", "cursor"]
        assert outcome.clients == 2
        assert outcome.mcp_declined is False

    def test_enter_on_the_first_row__registers_that_client_alone(self):
        """`All` sits under the clients, so a bare Enter is not select-all.

        With nothing ticked the picker takes the highlighted row, and that is
        now the first client. Registering every client is a row the user has to
        move to, which is the conservative reading of an ambiguous Enter.
        """
        from opik.cli import selector

        outcome, installed = self._pick([selector.ACCEPT])

        assert installed == ["claude-code"]
        assert outcome.clients == 1

    def test_enter_on_the_all_row__registers_every_client(self):
        from opik.cli import selector

        # Past both clients, onto the `All` row.
        outcome, installed = self._pick([selector.DOWN, selector.DOWN, selector.ACCEPT])

        assert sorted(installed) == ["claude-code", "cursor"]
        assert outcome.clients == 2

    def test_cancelling__registers_nothing_and_propagates_declined(self):
        from opik.cli import selector

        outcome, installed = self._pick([selector.CANCEL])

        assert installed == []
        assert outcome.clients == 0
        assert outcome.mcp_declined is True, "InstallReport.declined must survive"

    def test_choosing_one__registers_only_that_one(self):
        from opik.cli import selector

        # Down one row from the first client, tick it: the second client.
        outcome, installed = self._pick(
            [selector.DOWN, selector.TOGGLE, selector.ACCEPT]
        )

        assert installed == ["cursor"]
        assert outcome.registered_clients == ("cursor",)
