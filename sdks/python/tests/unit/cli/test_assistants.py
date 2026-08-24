"""Tests for the shared "set Opik up for your AI assistants" step."""

from unittest import mock

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
    spy = mock.Mock(return_value=True)
    monkeypatch.setattr(assistants.skills_installer, "setup_skills", spy)
    return spy


@pytest.fixture
def confirm(monkeypatch):
    spy = mock.Mock(return_value=True)
    monkeypatch.setattr(assistants.click, "confirm", spy)
    return spy


class TestSetup:
    def test_setup__registers_the_server_without_asking_first(
        self, mcp_spy, skills_spy, confirm
    ):
        """Running the command is the answer; there is no what-to-install question."""
        assistants.setup(_params(), host_keys=["cursor"])

        mcp_spy.assert_called_once()
        assert mcp_spy.call_args.kwargs["host_keys"] == ["cursor"]

    def test_setup__offers_the_pack_after_the_server(
        self, mcp_spy, skills_spy, confirm
    ):
        assistants.setup(_params(), host_keys=["cursor"])

        confirm.assert_called_once()
        assert skills_spy.call_args.args[0] == ["cursor"]

    def test_setup__the_pack_is_recommended_and_defaults_to_yes(
        self, mcp_spy, skills_spy, confirm
    ):
        assistants.setup(_params(), host_keys=["cursor"])

        assert confirm.call_args.kwargs["default"] is True
        assert "Recommended" in confirm.call_args.args[0]

    def test_setup__declining_the_pack__installs_only_the_server(
        self, mcp_spy, skills_spy, confirm
    ):
        confirm.return_value = False

        assistants.setup(_params(), host_keys=["cursor"])

        mcp_spy.assert_called_once()
        skills_spy.assert_not_called()

    def test_setup__pack_offered_for_the_hosts_the_server_reached(
        self, monkeypatch, skills_spy, confirm
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
        self, monkeypatch, skills_spy, confirm
    ):
        """No assistant was configured, so there is none to add a pack to."""
        monkeypatch.setattr(
            assistants.mcp_installer, "setup_mcp_server", mock.Mock(return_value=[])
        )

        assistants.setup(_params())

        confirm.assert_not_called()
        skills_spy.assert_not_called()

    def test_setup__skills_flag_true__installs_without_asking(
        self, mcp_spy, skills_spy, confirm
    ):
        assistants.setup(_params(), host_keys=["cursor"], skills_flag=True)

        confirm.assert_not_called()
        skills_spy.assert_called_once()

    def test_setup__skills_flag_false__skips_without_asking(
        self, mcp_spy, skills_spy, confirm
    ):
        assistants.setup(_params(), host_keys=["cursor"], skills_flag=False)

        confirm.assert_not_called()
        skills_spy.assert_not_called()

    def test_setup__only_one_closing_line_for_the_whole_step(
        self, mcp_spy, skills_spy, confirm
    ):
        """Each half used to print "restart your assistant" independently."""
        assistants.setup(_params(), host_keys=["cursor"])

        assert mcp_spy.call_args.kwargs["announce_next_steps"] is False
        assert skills_spy.call_args.kwargs["announce_next_steps"] is False

    def test_setup__local_server_flag__is_passed_through(
        self, mcp_spy, skills_spy, confirm
    ):
        assistants.setup(_params(), force_local_server=True, host_keys=["cursor"])

        assert mcp_spy.call_args.kwargs["force_local_server"] is True


class TestWantsSkillPack:
    def test_explicit_true__no_question(self, confirm):
        assert assistants._wants_skill_pack(True) is True
        confirm.assert_not_called()

    def test_explicit_false__no_question(self, confirm):
        assert assistants._wants_skill_pack(False) is False
        confirm.assert_not_called()

    def test_unset__asks_without_relisting_the_assistants(self, confirm):
        """The results table right above already names them."""
        assistants._wants_skill_pack(None)

        prompt = confirm.call_args.args[0]
        assert "skill pack" in prompt
        assert "Cursor" not in prompt
