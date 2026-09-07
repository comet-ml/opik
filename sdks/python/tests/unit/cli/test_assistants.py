"""Tests for the shared "set Opik up for your AI client" step.

This module composes two installers; it no longer decides anything. The decisions
arrive as `consent.Verdict`s, and their table is tested in
`tests/unit/configurator/test_consent.py` — so what is left to check here is
composition: which halves run, what the pack is installed into, and what the
closing block claims.
"""

import pathlib
from unittest import mock

import pytest

from opik.cli import assistants
from opik.configurator import consent

PROCEED = consent.Verdict(consent.Decision.PROCEED, consent.Reason.REQUESTED)
DECLINE = consent.Verdict(consent.Decision.SKIP, consent.Reason.DECLINED)
ASK = consent.Verdict(consent.Decision.ASK, consent.Reason.ASKING)


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
def mcp_spy(monkeypatch):
    spy = mock.Mock(return_value=["cursor"])
    monkeypatch.setattr(assistants.mcp_installer, "setup_mcp_server", spy)
    return spy


@pytest.fixture
def skills_spy(monkeypatch):
    spy = mock.Mock(return_value=_install_result())
    monkeypatch.setattr(assistants.skills_installer, "setup_skills", spy)
    return spy


@pytest.fixture(autouse=True)
def detected(monkeypatch):
    monkeypatch.setattr(
        assistants.skills_installer, "detected_host_keys", lambda: ["vscode"]
    )


@pytest.fixture
def rich_view(monkeypatch):
    """A view whose `step` is a real context manager, unlike a bare Mock."""
    view = mock.MagicMock()
    view.step.return_value.__enter__ = mock.Mock(return_value=None)
    view.step.return_value.__exit__ = mock.Mock(return_value=False)
    monkeypatch.setattr(assistants.install_view, "RichInstallView", lambda: view)
    return view


@pytest.fixture
def confirm(monkeypatch):
    spy = mock.Mock(return_value=True)
    monkeypatch.setattr(assistants.click, "confirm", spy)
    return spy


class TestTheHalvesAreIndependent:
    """Either step can run without the other.

    They used to be welded together — `setup` registered the server as its first
    act, whatever it was asked for — so `--no-install-mcp` could not be honoured.
    """

    def test_mcp_declined__server_is_not_registered(
        self, mcp_spy, skills_spy, rich_view
    ):
        assistants.setup(_params(), install_mcp=False, skills=PROCEED)

        mcp_spy.assert_not_called()

    def test_mcp_declined__pack_still_installs(self, mcp_spy, skills_spy, rich_view):
        assistants.setup(_params(), install_mcp=False, skills=PROCEED)

        skills_spy.assert_called_once()

    def test_mcp_declined__pack_goes_to_detected_clients(
        self, mcp_spy, skills_spy, rich_view
    ):
        """With no server step there is no list of clients it reached."""
        assistants.setup(_params(), install_mcp=False, skills=PROCEED)

        assert skills_spy.call_args.args[0] == ["vscode"]

    def test_pack_declined__server_still_registers(
        self, mcp_spy, skills_spy, rich_view
    ):
        outcome = assistants.setup(_params(), install_mcp=True, skills=DECLINE)

        mcp_spy.assert_called_once()
        skills_spy.assert_not_called()
        assert outcome == assistants.Outcome(clients=1, skills=False)

    def test_both_declined__nothing_runs(self, mcp_spy, skills_spy, rich_view):
        outcome = assistants.setup(_params(), install_mcp=False, skills=DECLINE)

        mcp_spy.assert_not_called()
        skills_spy.assert_not_called()
        assert outcome is assistants.NOTHING_DONE


class TestPackTargets:
    def test_pack_goes_to_the_clients_the_server_reached(
        self, mcp_spy, skills_spy, rich_view
    ):
        mcp_spy.return_value = ["cursor", "codex"]

        assistants.setup(_params(), install_mcp=True, skills=PROCEED)

        assert skills_spy.call_args.args[0] == ["cursor", "codex"]

    def test_server_registered_nothing__falls_back_to_detected(
        self, mcp_spy, skills_spy, rich_view
    ):
        mcp_spy.return_value = []

        assistants.setup(_params(), install_mcp=True, skills=PROCEED)

        assert skills_spy.call_args.args[0] == ["vscode"]


class TestAsking:
    def test_verdict_ask__prompts(self, mcp_spy, skills_spy, rich_view, confirm):
        assistants.setup(_params(), install_mcp=True, skills=ASK)

        confirm.assert_called_once()
        skills_spy.assert_called_once()

    def test_verdict_ask__declining_installs_only_the_server(
        self, mcp_spy, skills_spy, rich_view, confirm
    ):
        confirm.return_value = False

        assistants.setup(_params(), install_mcp=True, skills=ASK)

        skills_spy.assert_not_called()

    def test_decided_verdicts__never_prompt(
        self, mcp_spy, skills_spy, rich_view, confirm
    ):
        for verdict in (PROCEED, DECLINE):
            assistants.setup(_params(), install_mcp=True, skills=verdict)

        confirm.assert_not_called()

    def test_the_pack_is_recommended_and_defaults_to_yes(
        self, mcp_spy, skills_spy, rich_view, confirm
    ):
        assistants.setup(_params(), install_mcp=True, skills=ASK)

        assert confirm.call_args.kwargs["default"] is True
        assert "Recommended" in confirm.call_args.args[0]

    def test_the_prompt_does_not_relist_the_clients(
        self, mcp_spy, skills_spy, rich_view, confirm
    ):
        """The results table directly above it just named them."""
        assistants.setup(_params(), install_mcp=True, skills=ASK)

        assert "cursor" not in confirm.call_args.args[0].lower()


class TestClosingBlock:
    def test_one_closing_block_for_the_whole_step(self, mcp_spy, skills_spy, rich_view):
        assistants.setup(_params(), install_mcp=True, skills=PROCEED)

        assert rich_view.done.call_count == 1
        assert mcp_spy.call_args.kwargs["announce_next_steps"] is False

    def test_lists_both_components(self, mcp_spy, skills_spy, rich_view):
        assistants.setup(_params(), install_mcp=True, skills=PROCEED)

        assert rich_view.done.call_args.args[0] == ["MCP server", "skill pack"]

    def test_omits_a_pack_that_failed(self, mcp_spy, skills_spy, rich_view):
        skills_spy.return_value = _install_result(succeeded=False)

        outcome = assistants.setup(_params(), install_mcp=True, skills=PROCEED)

        assert rich_view.done.call_args.args[0] == ["MCP server"]
        assert outcome.skills is False

    def test_omits_a_server_that_reached_nothing(self, mcp_spy, skills_spy, rich_view):
        mcp_spy.return_value = []

        assistants.setup(_params(), install_mcp=True, skills=PROCEED)

        assert rich_view.done.call_args.args[0] == ["skill pack"]


class TestPassThrough:
    def test_local_server_flag(self, mcp_spy, skills_spy, rich_view):
        assistants.setup(
            _params(), install_mcp=True, skills=DECLINE, force_local_server=True
        )

        assert mcp_spy.call_args.kwargs["force_local_server"] is True

    def test_host_keys_and_assume_confirmed(self, mcp_spy, skills_spy, rich_view):
        assistants.setup(
            _params(),
            install_mcp=True,
            skills=DECLINE,
            host_keys=["codex"],
            assume_confirmed=True,
        )

        assert mcp_spy.call_args.kwargs["host_keys"] == ["codex"]
        assert mcp_spy.call_args.kwargs["assume_confirmed"] is True

    def test_connection_block(self, mcp_spy, skills_spy, rich_view):
        assistants.setup(_params(), install_mcp=True, skills=DECLINE)

        assert mcp_spy.call_args.kwargs["api_key"] == "key"
        assert mcp_spy.call_args.kwargs["workspace"] == "acme-ai"
