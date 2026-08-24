"""Tests for the shared "set Opik up for your AI assistant" step."""

from unittest import mock

import pathlib

import pytest

from opik.cli import assistants


def _params():
    return {
        "api_key": "key",
        "workspace": "acme-ai",
        "base_url": "https://www.comet.com/",
        "api_url": "https://www.comet.com/opik/api/",
        "use_local": False,
        "self_hosted_comet": False,
        "check_tls_certificate": True,
    }


@pytest.fixture
def mcp_spy(monkeypatch):
    spy = mock.Mock(return_value=["cursor"])
    monkeypatch.setattr(assistants.mcp_installer, "setup_mcp_server", spy)
    return spy


@pytest.fixture
def skills_spy(monkeypatch):
    spy = mock.Mock(return_value=_install_result())
    monkeypatch.setattr(assistants.skills_installer, "setup_skills", spy)
    return spy


def _install_result(succeeded=True, **overrides):
    from opik.configurator.skills import install as skills_install

    fields = dict(
        succeeded=succeeded,
        skills=["opik", "instrument"] if succeeded else [],
        shared_dir=pathlib.Path("/h/.agents/skills") if succeeded else None,
        error=None if succeeded else "boom",
    )
    fields.update(overrides)
    return skills_install.InstallResult(**fields)


@pytest.fixture
def rich_view(monkeypatch):
    """A view whose `step` is a real context manager, unlike a bare Mock."""
    view = mock.MagicMock()
    view.step.return_value.__enter__ = mock.Mock(return_value=None)
    view.step.return_value.__exit__ = mock.Mock(return_value=False)
    monkeypatch.setattr(assistants.mcp_rich_view, "RichInstallView", lambda: view)
    return view


@pytest.fixture(autouse=True)
def interactive(monkeypatch):
    """Default every test to a terminal; the non-interactive cases opt out."""
    monkeypatch.setattr(assistants.interactive_helpers, "is_interactive", lambda: True)


@pytest.fixture
def confirm(monkeypatch):
    spy = mock.Mock(return_value=True)
    monkeypatch.setattr(assistants.click, "confirm", spy)
    return spy


def _view():
    return mock.MagicMock()


class TestSetup:
    def test_setup__registers_the_server_without_asking_first(
        self, mcp_spy, skills_spy, confirm, rich_view
    ):
        """Running the command is the answer; there is no what-to-install question."""
        assistants.setup(_params(), host_keys=["cursor"])

        mcp_spy.assert_called_once()
        assert mcp_spy.call_args.kwargs["host_keys"] == ["cursor"]

    def test_setup__offers_the_pack_after_the_server(
        self, mcp_spy, skills_spy, confirm, rich_view
    ):
        assistants.setup(_params(), host_keys=["cursor"])

        confirm.assert_called_once()
        assert skills_spy.call_args.args[0] == ["cursor"]

    def test_setup__the_pack_is_recommended_and_defaults_to_yes(
        self, mcp_spy, skills_spy, confirm, rich_view
    ):
        assistants.setup(_params(), host_keys=["cursor"])

        assert confirm.call_args.kwargs["default"] is True
        assert "Recommended" in confirm.call_args.args[0]

    def test_setup__declining_the_pack__installs_only_the_server(
        self, mcp_spy, skills_spy, confirm, rich_view
    ):
        confirm.return_value = False

        assistants.setup(_params(), host_keys=["cursor"])

        mcp_spy.assert_called_once()
        skills_spy.assert_not_called()

    def test_setup__pack_offered_for_the_hosts_the_server_reached(
        self, monkeypatch, skills_spy, confirm, rich_view
    ):
        """Not the requested hosts — the ones that actually got registered."""
        monkeypatch.setattr(
            assistants.mcp_installer,
            "setup_mcp_server",
            mock.Mock(return_value=["claude-code"]),
        )

        assistants.setup(_params(), host_keys=["claude-code", "codex"])

        assert skills_spy.call_args.args[0] == ["claude-code"]

    def test_setup__server_registered_nothing__offers_nothing(
        self, monkeypatch, skills_spy, confirm, rich_view
    ):
        """No assistant was configured, so there is none to add a pack to."""
        monkeypatch.setattr(
            assistants.mcp_installer, "setup_mcp_server", mock.Mock(return_value=[])
        )

        assistants.setup(_params())

        confirm.assert_not_called()
        skills_spy.assert_not_called()

    def test_setup__skills_flag_true__installs_without_asking(
        self, mcp_spy, skills_spy, confirm, rich_view
    ):
        assistants.setup(_params(), host_keys=["cursor"], skills_flag=True)

        confirm.assert_not_called()
        skills_spy.assert_called_once()

    def test_setup__skills_flag_false__skips_without_asking(
        self, mcp_spy, skills_spy, confirm, rich_view
    ):
        assistants.setup(_params(), host_keys=["cursor"], skills_flag=False)

        confirm.assert_not_called()
        skills_spy.assert_not_called()

    def test_setup__only_one_closing_block_for_the_whole_step(
        self, mcp_spy, skills_spy, confirm, rich_view
    ):
        """Each half used to print "restart your assistant" independently."""
        assistants.setup(_params(), host_keys=["cursor"])

        # The server half is told to stay quiet; the pack half never announced
        # anything of its own, and the closing block is printed once, here.
        assert mcp_spy.call_args.kwargs["announce_next_steps"] is False
        rich_view.done.assert_called_once()

    def test_setup__done_block_lists_both_components(
        self, mcp_spy, skills_spy, confirm, rich_view
    ):
        assistants.setup(_params(), host_keys=["cursor"])

        assert rich_view.done.call_args.args[0] == ["MCP server", "skill pack"]

    def test_setup__done_block_omits_a_pack_that_failed(
        self, mcp_spy, monkeypatch, confirm, rich_view
    ):
        """Reporting a pack we could not install would be a lie."""
        monkeypatch.setattr(
            assistants.skills_installer,
            "setup_skills",
            mock.Mock(return_value=_install_result(succeeded=False)),
        )

        assistants.setup(_params(), host_keys=["cursor"])

        assert rich_view.done.call_args.args[0] == ["MCP server"]

    def test_setup__non_interactive_without_a_flag__skips_the_pack(
        self, mcp_spy, skills_spy, monkeypatch, rich_view
    ):
        """Asking aborted the run *after* the server had been registered."""
        monkeypatch.setattr(
            assistants.interactive_helpers, "is_interactive", lambda: False
        )
        confirm_spy = mock.Mock(side_effect=AssertionError("must not prompt"))
        monkeypatch.setattr(assistants.click, "confirm", confirm_spy)

        assistants.setup(_params(), host_keys=["cursor"])

        skills_spy.assert_not_called()

    def test_setup__non_interactive_with_skills_flag__installs(
        self, mcp_spy, skills_spy, monkeypatch, rich_view
    ):
        monkeypatch.setattr(
            assistants.interactive_helpers, "is_interactive", lambda: False
        )
        monkeypatch.setattr(
            assistants.click,
            "confirm",
            mock.Mock(side_effect=AssertionError("must not prompt")),
        )

        assistants.setup(_params(), host_keys=["cursor"], skills_flag=True)

        skills_spy.assert_called_once()

    def test_setup__local_server_flag__is_passed_through(
        self, mcp_spy, skills_spy, confirm, rich_view
    ):
        assistants.setup(_params(), force_local_server=True, host_keys=["cursor"])

        assert mcp_spy.call_args.kwargs["force_local_server"] is True


class TestWantsSkillPack:
    def test_explicit_true__no_question(self, confirm):
        assert assistants._wants_skill_pack(True, _view()) is True
        confirm.assert_not_called()

    def test_explicit_false__no_question(self, confirm):
        assert assistants._wants_skill_pack(False, _view()) is False
        confirm.assert_not_called()

    def test_non_interactive__does_not_ask(self, monkeypatch):
        monkeypatch.setattr(
            assistants.interactive_helpers, "is_interactive", lambda: False
        )
        confirm_spy = mock.Mock(side_effect=AssertionError("must not prompt"))
        monkeypatch.setattr(assistants.click, "confirm", confirm_spy)
        view = _view()

        assert assistants._wants_skill_pack(None, view) is False
        view.note.assert_called_once()
        assert "--skills" in view.note.call_args.args[0]

    def test_unset__asks_without_relisting_the_assistants(self, confirm):
        """The results table right above already names them."""
        assistants._wants_skill_pack(None, _view())

        prompt = confirm.call_args.args[0]
        assert "skill pack" in prompt
        assert "Cursor" not in prompt


class TestNonInteractiveSafety:
    """The pack offer must never abort a run that already changed the machine."""

    def test_setup__no_terminal__server_still_succeeds(
        self, monkeypatch, mcp_spy, skills_spy, rich_view
    ):
        monkeypatch.setattr(
            assistants.interactive_helpers, "is_interactive", lambda: False
        )
        monkeypatch.setattr(
            assistants.click,
            "confirm",
            mock.Mock(side_effect=AssertionError("must not prompt")),
        )

        assistants.setup(_params(), host_keys=["cursor"])

        mcp_spy.assert_called_once()
        skills_spy.assert_not_called()
        # The run still reports completion rather than dying half-way.
        rich_view.done.assert_called_once()
        assert rich_view.done.call_args.args[0] == ["MCP server"]

    def test_setup__no_terminal__says_how_to_opt_in(
        self, monkeypatch, mcp_spy, skills_spy, rich_view
    ):
        monkeypatch.setattr(
            assistants.interactive_helpers, "is_interactive", lambda: False
        )

        assistants.setup(_params(), host_keys=["cursor"])

        assert "--skills" in rich_view.note.call_args.args[0]
