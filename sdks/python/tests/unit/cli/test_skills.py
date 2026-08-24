"""Tests for the ``opik skills`` commands."""

import pathlib
from unittest.mock import patch

import pytest

from click.testing import CliRunner

from opik.cli import cli
from opik.cli import skills as skills_cli
from opik.configurator.skills import install as skills_install
from opik.configurator.skills import manifest as skills_manifest


def _ok(skills=("opik", "instrument")):
    return skills_install.InstallResult(
        succeeded=True,
        skills=list(skills),
        shared_dir=pathlib.Path("/h/.agents/skills"),
    )


def _failed(error="boom"):
    return skills_install.InstallResult(succeeded=False, error=error)


@pytest.fixture(autouse=True)
def interactive(monkeypatch):
    monkeypatch.setattr(skills_cli.interactive_helpers, "is_interactive", lambda: True)


class TestConfigureCommand:
    def test_configure__host_flag__installs_for_that_host_only(self):
        runner = CliRunner()
        with patch.object(
            skills_cli.skills_installer, "setup_skills", return_value=_ok()
        ) as setup_spy:
            result = runner.invoke(cli, ["skills", "configure", "--host", "codex"])

        assert result.exit_code == 0
        assert setup_spy.call_args.args[0] == ["codex"]

    def test_configure__repeated_host_flag__installs_for_each(self):
        runner = CliRunner()
        with patch.object(
            skills_cli.skills_installer, "setup_skills", return_value=_ok()
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
            skills_cli.skills_installer, "setup_skills", return_value=_ok()
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
                skills_cli.skills_installer, "setup_skills", return_value=_ok()
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
                skills_cli.skills_installer, "setup_skills", return_value=_ok()
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
            skills_cli.skills_installer, "setup_skills", return_value=_failed()
        ):
            result = runner.invoke(cli, ["skills", "configure", "--host", "codex"])

        assert result.exit_code != 0

    def test_configure__needs_no_opik_credentials(self):
        """Skills are documentation; they must install before `opik configure`."""
        runner = CliRunner()
        with patch.object(
            skills_cli.skills_installer, "setup_skills", return_value=_ok()
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


class TestUpdateCommand:
    def _result(self, **kwargs):
        from opik.configurator.skills import install as skills_install

        defaults = dict(changed=True, detail="updated to abcdef123456")
        defaults.update(kwargs)
        return skills_install.UpdateResult(**defaults)

    def test_update__changed__reports_the_version_and_the_restart(self):
        runner = CliRunner()
        with patch.object(
            skills_cli.skills_installer, "update_skills", return_value=self._result()
        ):
            result = runner.invoke(cli, ["skills", "update"])

        assert result.exit_code == 0
        assert "updated to abcdef123456" in result.output
        assert "Restart your AI host" in result.output

    def test_update__lists_added_and_removed_skills(self):
        runner = CliRunner()
        with patch.object(
            skills_cli.skills_installer,
            "update_skills",
            return_value=self._result(added=["evaluate"], removed=["instrument"]),
        ):
            result = runner.invoke(cli, ["skills", "update"])

        assert "added:   evaluate" in result.output
        assert "removed: instrument" in result.output

    def test_update__already_current__says_so_and_omits_the_restart(self):
        runner = CliRunner()
        with patch.object(
            skills_cli.skills_installer,
            "update_skills",
            return_value=self._result(changed=False, detail="already up to date (abc)"),
        ):
            result = runner.invoke(cli, ["skills", "update"])

        assert result.exit_code == 0
        assert "already up to date" in result.output
        assert "Restart" not in result.output

    def test_update__message_ends_with_exactly_one_full_stop(self):
        runner = CliRunner()
        with patch.object(
            skills_cli.skills_installer,
            "update_skills",
            return_value=self._result(changed=False, detail="nothing here."),
        ):
            result = runner.invoke(cli, ["skills", "update"])

        assert ".." not in result.output


class TestNonInteractiveEnvironments:
    """A closed stdin must produce an actionable error, never `Aborted!`.

    Both of these used to reach `click.confirm`, which aborts on EOF — and for
    `mcp configure` that happened *after* the server had already been registered.
    """

    @pytest.fixture(autouse=True)
    def non_interactive(self, monkeypatch):
        monkeypatch.setattr(
            skills_cli.interactive_helpers, "is_interactive", lambda: False
        )

    def test_configure__no_host__errors_pointing_at_the_flag(self):
        runner = CliRunner()
        with (
            patch.object(
                skills_cli.skills_roots,
                "detected_host_keys",
                return_value=["cursor", "codex"],
            ),
            patch.object(skills_cli.skills_installer, "setup_skills") as setup_spy,
        ):
            result = runner.invoke(cli, ["skills", "configure"])

        assert result.exit_code != 0
        assert "--host" in result.output
        assert "Aborted" not in result.output
        setup_spy.assert_not_called()

    def test_configure__with_host__still_works(self):
        runner = CliRunner()
        with patch.object(
            skills_cli.skills_installer, "setup_skills", return_value=_ok()
        ) as setup_spy:
            result = runner.invoke(cli, ["skills", "configure", "--host", "cursor"])

        assert result.exit_code == 0
        setup_spy.assert_called_once()

    def test_remove__without_yes__errors_pointing_at_the_flag(self):
        runner = CliRunner()
        with (
            patch.object(
                skills_manifest,
                "collect_status",
                return_value=[
                    skills_manifest.SkillStatus(
                        name="opik",
                        path=pathlib.Path("/h/.agents/skills/opik"),
                        content_hash="abc",
                        installed_at=None,
                        installed_by_opik=True,
                    )
                ],
            ),
            patch.object(skills_cli.skills_installer, "uninstall_skills") as spy,
        ):
            result = runner.invoke(cli, ["skills", "remove"])

        assert result.exit_code != 0
        assert "-y" in result.output
        assert "Aborted" not in result.output
        spy.assert_not_called()

    def test_status__works_without_a_terminal(self):
        runner = CliRunner()
        with patch.object(skills_manifest, "collect_status", return_value=[]):
            result = runner.invoke(cli, ["skills", "status"])

        assert result.exit_code == 0
