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
from opik.configurator.mcp import install as mcp_install

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


def _mcp_report(registered=("cursor",), failed=(), verified=True):
    return mcp_install.InstallReport(
        registered=tuple(registered), failed=tuple(failed), verified=verified
    )


@pytest.fixture
def mcp_spy(monkeypatch):
    spy = mock.Mock(return_value=_mcp_report())
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
        assert outcome == assistants.Outcome(
            clients=1,
            skills=False,
            registered_clients=("cursor",),
            verified=True,
            skills_decision="declined",
        )

    def test_both_declined__nothing_runs(self, mcp_spy, skills_spy, rich_view):
        outcome = assistants.setup(_params(), install_mcp=False, skills=DECLINE)

        mcp_spy.assert_not_called()
        skills_spy.assert_not_called()
        assert outcome == assistants.NOTHING_DONE._replace(skills_decision="declined")


class TestPackTargets:
    def test_pack_goes_to_the_clients_the_server_reached(
        self, mcp_spy, skills_spy, rich_view
    ):
        mcp_spy.return_value = _mcp_report(["cursor", "codex"])

        assistants.setup(_params(), install_mcp=True, skills=PROCEED)

        assert skills_spy.call_args.args[0] == ["cursor", "codex"]

    def test_server_registered_nothing__falls_back_to_detected(
        self, mcp_spy, skills_spy, rich_view
    ):
        mcp_spy.return_value = _mcp_report([])

        assistants.setup(_params(), install_mcp=True, skills=PROCEED)

        assert skills_spy.call_args.args[0] == ["vscode"]

    def test_client_not_listed__the_pack_follows_no_client(
        self, mcp_spy, skills_spy, rich_view
    ):
        """ "None of these is mine" is not an invitation to write to all of them.

        The fallback above is right for "not now" — the clients are still the
        user's, the server step was just declined. It is wrong for the user who
        has just said the detected list is not about them: it put the pack in
        every one of the clients they disowned. Naming none installs the shared
        copy and links nowhere.
        """
        mcp_spy.return_value = mcp_install.InstallReport(
            registered=(), declined=True, manual=True
        )

        assistants.setup(_params(), install_mcp=True, skills=PROCEED)

        assert skills_spy.call_args.args[0] == []


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

    def test_the_pack_defaults_to_yes(self, mcp_spy, skills_spy, rich_view, confirm):
        assistants.setup(_params(), install_mcp=True, skills=ASK)

        assert confirm.call_args.kwargs["default"] is True

    def test_the_pack_is_recommended_on_its_headline(
        self, mcp_spy, skills_spy, rich_view, confirm, capsys
    ):
        """Same shape as the MCP question: the recommendation rides the headline."""
        assistants.setup(_params(), install_mcp=True, skills=ASK)

        out = capsys.readouterr().out
        assert "Download the Opik skill pack for your AI client?" in out
        assert "(Recommended)" in out

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
        mcp_spy.return_value = _mcp_report([])

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


class TestSkillsDecisionIsRecorded:
    """The pack's answer is only known here, so only this can report it.

    `skills_installed=False` covered three different things — declined, never
    asked, and asked-for-but-failed-to-download — which made the pack's own
    accept rate unmeasurable.
    """

    def test_asked_and_accepted__requested(
        self, mcp_spy, skills_spy, rich_view, confirm
    ):
        confirm.return_value = True

        outcome = assistants.setup(_params(), install_mcp=True, skills=ASK)

        assert outcome.skills_decision == "requested"

    def test_asked_and_declined__declined(
        self, mcp_spy, skills_spy, rich_view, confirm
    ):
        confirm.return_value = False

        outcome = assistants.setup(_params(), install_mcp=True, skills=ASK)

        assert outcome.skills_decision == "declined"

    def test_flag__requested_without_asking(self, mcp_spy, skills_spy, rich_view):
        outcome = assistants.setup(_params(), install_mcp=True, skills=PROCEED)

        assert outcome.skills_decision == "requested"

    def test_never_asked__carries_the_verdict_reason(
        self, mcp_spy, skills_spy, rich_view
    ):
        nothing_detected = consent.Verdict(
            consent.Decision.SKIP, consent.Reason.NOTHING_DETECTED
        )

        outcome = assistants.setup(_params(), install_mcp=True, skills=nothing_detected)

        assert outcome.skills_decision == "nothing_detected"

    def test_accepted_but_install_failed__is_still_requested(
        self, mcp_spy, skills_spy, rich_view
    ):
        """A failed download is not a decline — the funnel has to tell them apart."""
        skills_spy.return_value = _install_result(succeeded=False)

        outcome = assistants.setup(_params(), install_mcp=True, skills=PROCEED)

        assert outcome.skills is False
        assert outcome.skills_decision == "requested"
