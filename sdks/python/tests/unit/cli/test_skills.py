"""Tests for the ``opik skills`` commands."""

import pathlib
from unittest.mock import patch

from click.testing import CliRunner

from opik.cli import cli
from opik.cli import skills as skills_cli
from opik.configurator.skills import manifest as skills_manifest


class TestConfigureCommand:
    def test_configure__host_flag__installs_for_that_host_only(self):
        runner = CliRunner()
        with patch.object(
            skills_cli.skills_installer, "setup_skills", return_value=True
        ) as setup_spy:
            result = runner.invoke(cli, ["skills", "configure", "--host", "codex"])

        assert result.exit_code == 0
        assert setup_spy.call_args.args[0] == ["codex"]

    def test_configure__repeated_host_flag__installs_for_each(self):
        runner = CliRunner()
        with patch.object(
            skills_cli.skills_installer, "setup_skills", return_value=True
        ) as setup_spy:
            result = runner.invoke(
                cli,
                ["skills", "configure", "--host", "codex", "--host", "claude-code"],
            )

        assert result.exit_code == 0
        assert setup_spy.call_args.args[0] == ["codex", "claude-code"]

    def test_configure__duplicate_host__deduplicates(self):
        runner = CliRunner()
        with patch.object(
            skills_cli.skills_installer, "setup_skills", return_value=True
        ) as setup_spy:
            result = runner.invoke(
                cli, ["skills", "configure", "--host", "codex", "--host", "codex"]
            )

        assert result.exit_code == 0
        assert setup_spy.call_args.args[0] == ["codex"]

    def test_configure__no_flag__defaults_to_detected_hosts(self):
        runner = CliRunner()
        with (
            patch.object(
                skills_cli.skills_roots, "detected_host_keys", return_value=["cursor"]
            ),
            patch.object(
                skills_cli.skills_installer, "setup_skills", return_value=True
            ) as setup_spy,
        ):
            result = runner.invoke(cli, ["skills", "configure"])

        assert result.exit_code == 0
        assert setup_spy.call_args.args[0] == ["cursor"]

    def test_configure__host_all__expands_to_detected(self):
        runner = CliRunner()
        with (
            patch.object(
                skills_cli.skills_roots,
                "detected_host_keys",
                return_value=["cursor", "codex"],
            ),
            patch.object(
                skills_cli.skills_installer, "setup_skills", return_value=True
            ) as setup_spy,
        ):
            result = runner.invoke(cli, ["skills", "configure", "--host", "all"])

        assert result.exit_code == 0
        assert setup_spy.call_args.args[0] == ["cursor", "codex"]

    def test_configure__host_all_nothing_detected__errors(self):
        runner = CliRunner()
        with (
            patch.object(
                skills_cli.skills_roots, "detected_host_keys", return_value=[]
            ),
            patch.object(skills_cli.skills_installer, "setup_skills") as setup_spy,
        ):
            result = runner.invoke(cli, ["skills", "configure", "--host", "all"])

        assert result.exit_code != 0
        assert "no supported AI host" in result.output
        setup_spy.assert_not_called()

    def test_configure__nothing_detected_and_no_flag__errors_with_a_suggestion(self):
        runner = CliRunner()
        with (
            patch.object(
                skills_cli.skills_roots, "detected_host_keys", return_value=[]
            ),
            patch.object(skills_cli.skills_installer, "setup_skills") as setup_spy,
        ):
            result = runner.invoke(cli, ["skills", "configure"])

        assert result.exit_code != 0
        assert "--host" in result.output
        setup_spy.assert_not_called()

    def test_configure__unknown_host__rejected_by_the_parser(self):
        runner = CliRunner()
        result = runner.invoke(cli, ["skills", "configure", "--host", "emacs"])

        assert result.exit_code != 0
        assert "emacs" in result.output

    def test_configure__install_fails__exits_nonzero(self):
        runner = CliRunner()
        with patch.object(
            skills_cli.skills_installer, "setup_skills", return_value=False
        ):
            result = runner.invoke(cli, ["skills", "configure", "--host", "codex"])

        assert result.exit_code != 0

    def test_configure__needs_no_opik_credentials(self):
        """Skills are documentation; they must install before `opik configure`."""
        runner = CliRunner()
        with patch.object(
            skills_cli.skills_installer, "setup_skills", return_value=True
        ) as setup_spy:
            result = runner.invoke(cli, ["skills", "configure", "--host", "codex"])

        assert result.exit_code == 0
        setup_spy.assert_called_once()


class TestRemoveCommand:
    def _status(self, name="opik", installed_by_opik=True):
        return skills_manifest.SkillStatus(
            name=name,
            path=pathlib.Path("/tmp/.agents/skills") / name,
            content_hash="abc",
            installed_at="2026-08-21T00:00:00Z",
            installed_by_opik=installed_by_opik,
        )

    def test_remove__confirmed__uninstalls(self):
        runner = CliRunner()
        with (
            patch.object(
                skills_manifest, "collect_status", return_value=[self._status()]
            ),
            patch.object(
                skills_cli.skills_installer, "uninstall_skills", return_value=["opik"]
            ) as uninstall_spy,
        ):
            result = runner.invoke(cli, ["skills", "remove"], input="y\n")

        assert result.exit_code == 0
        uninstall_spy.assert_called_once()
        assert "Removed opik" in result.output

    def test_remove__declined__leaves_it_alone(self):
        runner = CliRunner()
        with (
            patch.object(
                skills_manifest, "collect_status", return_value=[self._status()]
            ),
            patch.object(skills_cli.skills_installer, "uninstall_skills") as spy,
        ):
            result = runner.invoke(cli, ["skills", "remove"], input="n\n")

        assert result.exit_code == 0
        spy.assert_not_called()

    def test_remove__yes_flag__skips_the_prompt(self):
        runner = CliRunner()
        with (
            patch.object(
                skills_manifest, "collect_status", return_value=[self._status()]
            ),
            patch.object(
                skills_cli.skills_installer, "uninstall_skills", return_value=["opik"]
            ) as spy,
        ):
            result = runner.invoke(cli, ["skills", "remove", "-y"])

        assert result.exit_code == 0
        spy.assert_called_once()

    def test_remove__only_external_installs__removes_nothing(self):
        runner = CliRunner()
        with (
            patch.object(
                skills_manifest,
                "collect_status",
                return_value=[self._status(installed_by_opik=False)],
            ),
            patch.object(skills_cli.skills_installer, "uninstall_skills") as spy,
        ):
            result = runner.invoke(cli, ["skills", "remove", "-y"])

        assert result.exit_code == 0
        assert "nothing to remove" in result.output
        spy.assert_not_called()


class TestStatusCommand:
    def test_status__nothing_installed__suggests_configure(self):
        runner = CliRunner()
        with patch.object(skills_manifest, "collect_status", return_value=[]):
            result = runner.invoke(cli, ["skills", "status"])

        assert result.exit_code == 0
        assert "No Opik skills are installed" in result.output
        assert "opik skills configure" in result.output

    def test_status__installed_by_opik__shows_version_and_link_state(self):
        runner = CliRunner()
        status = skills_manifest.SkillStatus(
            name="opik",
            path=pathlib.Path("/home/u/.agents/skills/opik"),
            content_hash="0123456789abcdef",
            installed_at="2026-08-21T00:00:00Z",
            installed_by_opik=True,
            linked_hosts={"claude-code": False},
        )
        with patch.object(skills_manifest, "collect_status", return_value=[status]):
            result = runner.invoke(cli, ["skills", "status"])

        assert result.exit_code == 0
        assert "0123456789ab" in result.output
        assert "not linked" in result.output

    def test_status__external_install__is_flagged(self):
        runner = CliRunner()
        status = skills_manifest.SkillStatus(
            name="opik",
            path=pathlib.Path("/home/u/.agents/skills/opik"),
            content_hash=None,
            installed_at=None,
            installed_by_opik=False,
        )
        with patch.object(skills_manifest, "collect_status", return_value=[status]):
            result = runner.invoke(cli, ["skills", "status"])

        assert result.exit_code == 0
        assert "outside this CLI" in result.output
