"""Tests for the shared "set Opik up for your AI assistants" step."""

from unittest import mock

import click
import pytest

from opik.cli import assistants


class TestChooseComponents:
    def test_skills_flag_false__server_only(self):
        assert assistants.choose_components(False) == [assistants.COMPONENT_MCP]

    def test_skills_flag_true__both_without_asking(self):
        assert assistants.choose_components(True) == [
            assistants.COMPONENT_MCP,
            assistants.COMPONENT_SKILLS,
        ]

    def test_no_picker_support__defaults_to_both(self, monkeypatch):
        monkeypatch.setattr(assistants.selector, "is_supported", lambda: False)

        assert assistants.choose_components(None) == [
            assistants.COMPONENT_MCP,
            assistants.COMPONENT_SKILLS,
        ]

    def test_both_are_preselected(self, monkeypatch):
        """ "MCP + skills" must be what Enter accepts."""
        monkeypatch.setattr(assistants.selector, "is_supported", lambda: True)
        picker = mock.Mock(return_value=[assistants.COMPONENT_MCP])
        monkeypatch.setattr(assistants.selector, "multiselect", picker)

        assistants.choose_components(None)

        assert picker.call_args.kwargs["preselected"] == [
            assistants.COMPONENT_MCP,
            assistants.COMPONENT_SKILLS,
        ]

    def test_cancelled__aborts(self, monkeypatch):
        monkeypatch.setattr(assistants.selector, "is_supported", lambda: True)
        monkeypatch.setattr(assistants.selector, "multiselect", lambda **k: None)

        with pytest.raises(click.ClickException, match="Cancelled"):
            assistants.choose_components(None)


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


class TestSetup:
    @pytest.fixture(autouse=True)
    def no_picker(self, monkeypatch):
        monkeypatch.setattr(assistants.selector, "is_supported", lambda: False)

    def test_setup__default__installs_the_server_and_the_pack(self, monkeypatch):
        mcp_spy = mock.Mock(return_value=["cursor"])
        skills_spy = mock.Mock(return_value=True)
        monkeypatch.setattr(assistants.mcp_installer, "setup_mcp_server", mcp_spy)
        monkeypatch.setattr(assistants.skills_installer, "setup_skills", skills_spy)

        assistants.setup(_params(), host_keys=["cursor"])

        mcp_spy.assert_called_once()
        assert skills_spy.call_args.args[0] == ["cursor"]

    def test_setup__reuses_the_hosts_the_server_was_set_up_for(self, monkeypatch):
        """The assistants question must not be asked twice in one run."""
        monkeypatch.setattr(
            assistants.mcp_installer,
            "setup_mcp_server",
            mock.Mock(return_value=["claude-code", "codex"]),
        )
        ask_spy = mock.Mock()
        monkeypatch.setattr(assistants, "_ask_which_hosts", ask_spy)
        skills_spy = mock.Mock(return_value=True)
        monkeypatch.setattr(assistants.skills_installer, "setup_skills", skills_spy)

        assistants.setup(_params())

        assert skills_spy.call_args.args[0] == ["claude-code", "codex"]
        ask_spy.assert_not_called()

    def test_setup__no_skills__installs_only_the_server(self, monkeypatch):
        monkeypatch.setattr(
            assistants.mcp_installer,
            "setup_mcp_server",
            mock.Mock(return_value=["cursor"]),
        )
        skills_spy = mock.Mock()
        monkeypatch.setattr(assistants.skills_installer, "setup_skills", skills_spy)

        assistants.setup(_params(), host_keys=["cursor"], skills_flag=False)

        skills_spy.assert_not_called()

    def test_setup__plan_mentions_the_pack_when_included(self, monkeypatch):
        mcp_spy = mock.Mock(return_value=["cursor"])
        monkeypatch.setattr(assistants.mcp_installer, "setup_mcp_server", mcp_spy)
        monkeypatch.setattr(
            assistants.skills_installer, "setup_skills", mock.Mock(return_value=True)
        )

        assistants.setup(_params(), host_keys=["cursor"])

        extras = mcp_spy.call_args.kwargs["plan_extras"]
        assert [e.display_name for e in extras] == ["Skill pack"]

    def test_setup__only_one_closing_line_when_both_run(self, monkeypatch):
        """Each half printed "restart your assistant"; the user needs it once."""
        mcp_spy = mock.Mock(return_value=["cursor"])
        skills_spy = mock.Mock(return_value=True)
        monkeypatch.setattr(assistants.mcp_installer, "setup_mcp_server", mcp_spy)
        monkeypatch.setattr(assistants.skills_installer, "setup_skills", skills_spy)

        assistants.setup(_params(), host_keys=["cursor"])

        assert mcp_spy.call_args.kwargs["announce_next_steps"] is False
        assert skills_spy.call_args.kwargs["announce_next_steps"] is False

    def test_setup__server_only__announces_its_own_next_steps(self, monkeypatch):
        mcp_spy = mock.Mock(return_value=["cursor"])
        monkeypatch.setattr(assistants.mcp_installer, "setup_mcp_server", mcp_spy)

        assistants.setup(_params(), host_keys=["cursor"], skills_flag=False)

        assert mcp_spy.call_args.kwargs["announce_next_steps"] is True

    def test_setup__server_configured_nothing__does_not_install_the_pack_blindly(
        self, monkeypatch
    ):
        """No successful host means no host to install a pack for."""
        monkeypatch.setattr(
            assistants.mcp_installer, "setup_mcp_server", mock.Mock(return_value=[])
        )
        monkeypatch.setattr(assistants, "_ask_which_hosts", mock.Mock(return_value=[]))
        skills_spy = mock.Mock()
        monkeypatch.setattr(assistants.skills_installer, "setup_skills", skills_spy)

        assistants.setup(_params())

        skills_spy.assert_not_called()

    def test_setup__pack_only__asks_which_hosts(self, monkeypatch):
        monkeypatch.setattr(
            assistants.selector,
            "multiselect",
            lambda **k: [assistants.COMPONENT_SKILLS],
        )
        monkeypatch.setattr(assistants.selector, "is_supported", lambda: True)
        mcp_spy = mock.Mock()
        monkeypatch.setattr(assistants.mcp_installer, "setup_mcp_server", mcp_spy)
        monkeypatch.setattr(
            assistants, "_ask_which_hosts", mock.Mock(return_value=["codex"])
        )
        skills_spy = mock.Mock(return_value=True)
        monkeypatch.setattr(assistants.skills_installer, "setup_skills", skills_spy)

        assistants.setup(_params())

        mcp_spy.assert_not_called()
        assert skills_spy.call_args.args[0] == ["codex"]

    def test_setup__cancelled_host_question__aborts(self, monkeypatch):
        monkeypatch.setattr(
            assistants.mcp_installer, "setup_mcp_server", mock.Mock(return_value=[])
        )
        monkeypatch.setattr(
            assistants, "_ask_which_hosts", mock.Mock(return_value=None)
        )

        with pytest.raises(click.ClickException, match="Cancelled"):
            assistants.setup(_params())
