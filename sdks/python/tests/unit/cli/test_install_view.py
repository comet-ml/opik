"""Tests for the CLI's rich renderer.

Lives in the CLI suite, not the configurator one: it exercises
``opik.cli.install_view`` and ``rich`` rendering, so keeping it next to
``configurator.mcp.view`` made that suite depend on the CLI layer it is meant to
be independent of.
"""

import pathlib
from unittest import mock

import pytest

from opik.configurator.mcp import view as mcp_view


def _targets():
    return [
        mcp_view.PlannedTarget("Cursor", "~/.cursor/mcp.json"),
        mcp_view.PlannedTarget("Claude Code", "via `claude mcp add`"),
    ]


class TestRichInstallView:
    """Rendering only — asserted through rich's own capture, not by eyeballing."""

    @pytest.fixture
    def view(self):
        from opik.cli import install_view as rich_view

        return rich_view

    def test_plan__shows_deployment_transport_and_every_path(self, view):
        with view.console.capture() as capture:
            view.RichInstallView().plan(
                "Opik Cloud · workspace acme-ai", "Local server via uvx", _targets()
            )

        out = capture.get()
        assert "Opik MCP server setup" in out
        assert "acme-ai" in out
        assert "Will update" in out
        assert "~/.cursor/mcp.json" in out
        assert "Claude Code" in out

    def test_results__success_uses_the_short_form(self, view):
        """The path was already shown in the plan; repeating it just wraps."""
        with view.console.capture() as capture:
            view.RichInstallView().results(
                [
                    mcp_view.TargetResult(
                        "Cursor", "Added 'opik-mcp' in /very/long/path", True, "Added"
                    )
                ]
            )

        out = capture.get()
        assert "Added" in out
        assert "/very/long/path" not in out

    def test_results__failure_keeps_the_full_detail(self, view):
        with view.console.capture() as capture:
            view.RichInstallView().results(
                [mcp_view.TargetResult("Codex", "the `codex` CLI was not found", False)]
            )

        assert "was not found" in capture.get()

    def test_verification__failure_says_not_working(self, view):
        with view.console.capture() as capture:
            view.RichInstallView().verification(False, "HTTP 401")

        out = capture.get()
        assert "Not working" in out
        assert "HTTP 401" in out

    def test_done__joins_names_readably_and_marks_completion(self, view):
        with view.console.capture() as capture:
            view.RichInstallView().done(
                ["MCP server", "skill pack"], ["Cursor", "Claude Code", "Codex"]
            )

        out = capture.get()
        assert "Done" in out
        assert "MCP server and skill pack" in out
        assert "Cursor, Claude Code and Codex" in out
        assert "list my Opik projects" in out

    def test_done__single_assistant__says_restart_it(self, view):
        with view.console.capture() as capture:
            view.RichInstallView().done(["MCP server"], ["Cursor"])

        assert "Restart it" in capture.get()

    def test_done__suggested_prompt_is_green(self, view, monkeypatch):
        """It is the one thing here the user is meant to copy, so it stands out."""
        import rich.console

        recorder = rich.console.Console(force_terminal=True, width=100)
        monkeypatch.setattr(view, "console", recorder)

        with recorder.capture() as capture:
            view.RichInstallView().done(["MCP server"], ["Cursor"])

        # Anchored to the prompt itself: the ✓ above is also green (`1;32`), so a
        # bare search for the colour would pass even if the prompt lost it.
        assert '\x1b[32m"list my Opik projects via Opik MCP"' in capture.get()

    def test_done__sign_in_needed__hint_comes_after_the_next_step(self, view):
        installer = view.RichInstallView()
        installer.plan("Opik Cloud", "Hosted server", [], needs_sign_in=True)
        with view.console.capture() as capture:
            installer.done(["MCP server"], ["Claude Code"])

        out = capture.get()
        assert "Signing in" in out
        assert out.index("list my Opik projects") < out.index("Signing in")

    def test_done__no_sign_in__stays_quiet(self, view):
        """The local server takes its credentials at startup — nothing to sign in to."""
        installer = view.RichInstallView()
        installer.plan("Local Opik", "Local server via uvx", [], needs_sign_in=False)
        with view.console.capture() as capture:
            installer.done(["MCP server"], ["Cursor"])

        assert "Signing in" not in capture.get()

    def test_step__propagates_exceptions(self, view):
        with pytest.raises(ValueError):
            with view.RichInstallView().step("probing"):
                raise ValueError("boom")

    @pytest.mark.parametrize(
        ("names", "expected"),
        [
            ([], ""),
            (["Cursor"], "Cursor"),
            (["Cursor", "Codex"], "Cursor and Codex"),
            (["a", "b", "c"], "a, b and c"),
        ],
    )
    def test_join(self, view, names, expected):
        assert view._join(names) == expected

    def test_failure_detail__collapses_home_paths(self, view, monkeypatch, tmp_path):
        """One absolute path wraps over three lines and buries the instruction."""
        monkeypatch.setattr(pathlib.Path, "home", classmethod(lambda cls: tmp_path))
        detail = f"{tmp_path}/.codex/config.toml is TOML"

        with view.console.capture() as capture:
            view.RichInstallView().results(
                [mcp_view.TargetResult("Codex", detail, False)]
            )

        out = capture.get()
        assert "~/.codex/config.toml" in out
        assert str(tmp_path) not in out

    def test_problem__collapses_home_paths(self, view, monkeypatch, tmp_path):
        monkeypatch.setattr(pathlib.Path, "home", classmethod(lambda cls: tmp_path))

        with view.console.capture() as capture:
            view.RichInstallView().problem(
                f"could not write {tmp_path}/.cursor/mcp.json"
            )

        assert "~/.cursor/mcp.json" in capture.get()


class TestChooseHosts:
    """Selection is presentation, so it lives with the views."""

    def _candidates(self):
        return [
            mcp_view.HostChoice("claude-code", "Claude Code"),
            mcp_view.HostChoice("cursor", "Cursor"),
            mcp_view.HostChoice("codex", "Codex"),
        ]

    def test_logging_view__single_candidate__is_a_yes_no(self, monkeypatch):
        """A one-item numbered menu would be silly."""
        monkeypatch.setattr("builtins.input", lambda prompt: "y")

        chosen = mcp_view.LoggingInstallView().choose_hosts(
            "pick", [mcp_view.HostChoice("cursor", "Cursor")], ["cursor"]
        )

        assert chosen == ["cursor"]

    def test_logging_view__single_candidate_declined(self, monkeypatch):
        monkeypatch.setattr("builtins.input", lambda prompt: "n")

        chosen = mcp_view.LoggingInstallView().choose_hosts(
            "pick", [mcp_view.HostChoice("cursor", "Cursor")], []
        )

        assert chosen == []

    def test_logging_view__menu_lists_every_candidate(self, monkeypatch):
        prompts = []

        def fake_input(prompt):
            prompts.append(prompt)
            return "5"  # Skip (3 hosts -> 4 all, 5 skip)

        monkeypatch.setattr("builtins.input", fake_input)

        mcp_view.LoggingInstallView().choose_hosts("pick", self._candidates(), [])

        assert "Claude Code" in prompts[0]
        assert "All of the above" in prompts[0]

    def test_logging_view__all_of_the_above(self, monkeypatch):
        monkeypatch.setattr("builtins.input", lambda prompt: "4")

        chosen = mcp_view.LoggingInstallView().choose_hosts(
            "pick", self._candidates(), []
        )

        assert chosen == ["claude-code", "cursor", "codex"]

    def test_logging_view__comma_separated_subset(self, monkeypatch):
        monkeypatch.setattr("builtins.input", lambda prompt: "1,3")

        chosen = mcp_view.LoggingInstallView().choose_hosts(
            "pick", self._candidates(), []
        )

        assert chosen == ["claude-code", "codex"]

    def test_logging_view__skip(self, monkeypatch):
        monkeypatch.setattr("builtins.input", lambda prompt: "5")

        assert (
            mcp_view.LoggingInstallView().choose_hosts("pick", self._candidates(), [])
            == []
        )

    def test_logging_view__invalid_then_valid__retries(self, monkeypatch):
        monkeypatch.setattr("builtins.input", mock.Mock(side_effect=["x", "99", "2"]))

        chosen = mcp_view.LoggingInstallView().choose_hosts(
            "pick", self._candidates(), []
        )

        assert chosen == ["cursor"]

    def test_rich_view__uses_the_picker_when_the_terminal_allows(self, monkeypatch):
        from opik.cli import install_view as rich_view
        from opik.cli import selector

        monkeypatch.setattr(selector, "is_supported", lambda: True)
        monkeypatch.setattr(selector, "multiselect", lambda **kwargs: ["codex"])

        chosen = rich_view.RichInstallView().choose_hosts(
            "pick", self._candidates(), ["claude-code"]
        )

        assert chosen == ["codex"]

    def test_rich_view__no_picker_support__falls_back_to_the_menu(self, monkeypatch):
        from opik.cli import install_view as rich_view
        from opik.cli import selector

        monkeypatch.setattr(selector, "is_supported", lambda: False)
        monkeypatch.setattr("builtins.input", lambda prompt: "4")

        chosen = rich_view.RichInstallView().choose_hosts(
            "pick", self._candidates(), []
        )

        assert chosen == ["claude-code", "cursor", "codex"]

    def test_rich_view__single_candidate__skips_the_picker(self, monkeypatch):
        from opik.cli import install_view as rich_view
        from opik.cli import selector

        monkeypatch.setattr(selector, "is_supported", lambda: True)
        monkeypatch.setattr(
            selector,
            "multiselect",
            mock.Mock(side_effect=AssertionError("no picker for one item")),
        )
        monkeypatch.setattr("builtins.input", lambda prompt: "y")

        chosen = rich_view.RichInstallView().choose_hosts(
            "pick", [mcp_view.HostChoice("cursor", "Cursor")], ["cursor"]
        )

        assert chosen == ["cursor"]

    def test_rich_view__cancelled_picker__propagates_none(self, monkeypatch):
        from opik.cli import install_view as rich_view
        from opik.cli import selector

        monkeypatch.setattr(selector, "is_supported", lambda: True)
        monkeypatch.setattr(selector, "multiselect", lambda **kwargs: None)

        assert (
            rich_view.RichInstallView().choose_hosts("pick", self._candidates(), [])
            is None
        )
