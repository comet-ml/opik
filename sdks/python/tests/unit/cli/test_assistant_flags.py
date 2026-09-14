"""What `opik configure`'s flags actually cause to be written.

These assert at the boundary that matters: which *installers ran*. The existing
CLI tests mock `assistants.setup` wholesale, so they cannot see that it registered
an MCP server — which is how `--no-install-mcp` shipped registering one anyway.
"""

from unittest import mock

import pytest
from click.testing import CliRunner

from opik.cli import cli
from opik.cli import assistants as cli_assistants
from opik.cli import configure as configure_cli
from opik.configurator import mcp as mcp_installer
from opik.configurator import skills as skills_installer
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
        return ["cursor"]

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
                mcp_installer, "detected_host_names", return_value=["Cursor"]
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
