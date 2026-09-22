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

    def test_plan__shows_deployment_and_transport(self, view):
        with view.console.capture() as capture:
            view.RichInstallView().plan(
                "Opik Cloud · workspace acme-ai", "Local server via uvx", _targets()
            )

        out = capture.get()
        assert "Opik MCP server setup" in out
        assert "acme-ai" in out
        assert "Local server via uvx" in out

    def test_plan__does_not_relist_the_clients_and_their_paths(self, view):
        """The prompt listed them, the picker listed them; the results table
        below reports what was actually written."""
        with view.console.capture() as capture:
            view.RichInstallView().plan(
                "Opik Cloud · workspace acme-ai", "Local server via uvx", _targets()
            )

        out = capture.get()
        assert "Will update" not in out
        assert "~/.cursor/mcp.json" not in out

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
        """3 candidates, so 4 is All, 5 is "not listed" and 6 is Skip."""
        monkeypatch.setattr("builtins.input", lambda prompt: "6")

        assert (
            mcp_view.LoggingInstallView().choose_hosts("pick", self._candidates(), [])
            == []
        )

    def test_logging_view__client_not_listed(self, monkeypatch):
        monkeypatch.setattr("builtins.input", lambda prompt: "5")

        assert mcp_view.LoggingInstallView().choose_hosts(
            "pick", self._candidates(), []
        ) == [mcp_view.MANUAL_SETUP]

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

    def test_rich_view__single_candidate__still_offers_the_manual_row(
        self, monkeypatch
    ):
        """One client used to skip the picker, and the manual row lives in it.

        So the user this most concerns — one client detected, and it is not
        theirs — met a yes/no where "my AI client is not listed" belonged, and
        a no printed "Skipped" instead of the config they needed.
        """
        from opik.cli import install_view as rich_view
        from opik.cli import selector

        monkeypatch.setattr(selector, "is_supported", lambda: True)
        offered = {}
        monkeypatch.setattr(
            selector,
            "multiselect",
            lambda **kwargs: offered.update(kwargs) or [mcp_view.MANUAL_SETUP],
        )

        chosen = rich_view.RichInstallView().choose_hosts(
            "pick", [mcp_view.HostChoice("cursor", "Cursor")], ["cursor"]
        )

        assert chosen == [mcp_view.MANUAL_SETUP]
        labels = [choice.label for choice in offered["choices"]]
        assert labels == ["Cursor", mcp_view.MANUAL_SETUP_LABEL]

    def test_rich_view__single_candidate__offers_no_all_row(self, monkeypatch):
        """Nothing for it to stand in for, and it would outnumber the clients."""
        from opik.cli import install_view as rich_view
        from opik.cli import selector

        monkeypatch.setattr(selector, "is_supported", lambda: True)
        offered = {}
        monkeypatch.setattr(
            selector,
            "multiselect",
            lambda **kwargs: offered.update(kwargs) or ["cursor"],
        )

        rich_view.RichInstallView().choose_hosts(
            "pick", [mcp_view.HostChoice("cursor", "Cursor")], []
        )

        assert "All" not in [choice.label for choice in offered["choices"]]

    def test_rich_view__cancelled_picker__propagates_none(self, monkeypatch):
        from opik.cli import install_view as rich_view
        from opik.cli import selector

        monkeypatch.setattr(selector, "is_supported", lambda: True)
        monkeypatch.setattr(selector, "multiselect", lambda **kwargs: None)

        assert (
            rich_view.RichInstallView().choose_hosts("pick", self._candidates(), [])
            is None
        )


class TestTheAllRow:
    """The picker offers "All" after the clients, before the manual row.

    The same order the numbered-menu fallback has always used: the rows the
    user is choosing between come first, and the two catch-alls sit under them.
    Nothing is pre-ticked — this writes into other tools' config files — and
    `multiselect` takes the highlighted row when the selection is empty, so a
    bare Enter registers the first (highest-priority) client, and choosing
    every client stays deliberate.
    """

    @staticmethod
    def _candidates():
        return [
            mcp_view.HostChoice("claude-code", "Claude Code"),
            mcp_view.HostChoice("codex", "Codex"),
            mcp_view.HostChoice("cursor", "Cursor"),
        ]

    def _choose(self, monkeypatch, returns):
        from opik.cli import install_view as rich_view
        from opik.cli import selector

        seen = {}

        def fake(**kwargs):
            seen["choices"] = kwargs["choices"]
            return returns

        monkeypatch.setattr(selector, "is_supported", lambda: True)
        monkeypatch.setattr(selector, "multiselect", fake)
        chosen = rich_view.RichInstallView().choose_hosts(
            "pick", self._candidates(), []
        )
        return chosen, seen["choices"]

    def test_clients_first_then_all_then_not_listed(self, monkeypatch):
        _, choices = self._choose(monkeypatch, [])

        assert [c.label for c in choices] == [
            "Claude Code",
            "Codex",
            "Cursor",
            "All",
            mcp_view.MANUAL_SETUP_LABEL,
        ]

    def test_no_skip_row(self, monkeypatch):
        """Escape is the silent decline; the extra row is the one with an answer."""
        _, choices = self._choose(monkeypatch, [])

        assert "Skip" not in [c.label for c in choices]

    def test_not_listed__returns_the_sentinel_alone(self, monkeypatch):
        """It must not reach the installer as a host key, or nothing installs."""
        chosen, _ = self._choose(monkeypatch, [mcp_view.MANUAL_SETUP, "codex"])

        assert chosen == [mcp_view.MANUAL_SETUP]

    def test_choosing_all__expands_to_every_candidate(self, monkeypatch):
        from opik.cli import install_view as rich_view

        chosen, _ = self._choose(monkeypatch, [rich_view._ALL])

        assert chosen == ["claude-code", "codex", "cursor"]

    def test_choosing_some__returns_only_those(self, monkeypatch):
        chosen, _ = self._choose(monkeypatch, ["codex", "cursor"])

        assert chosen == ["codex", "cursor"]

    def test_the_sentinel_never_leaks_out(self, monkeypatch):
        """It is not a host key; passing it downstream would install nothing."""
        from opik.cli import install_view as rich_view

        chosen, _ = self._choose(monkeypatch, ["codex", rich_view._ALL])

        assert rich_view._ALL not in chosen

    def test_cancelled__still_propagates_none(self, monkeypatch):
        chosen, _ = self._choose(monkeypatch, None)

        assert chosen is None


class TestLinks:
    """URLs are coloured and clickable, without breaking plainer terminals.

    One call has to cover all three: an OSC 8 hyperlink where the terminal
    advertises support, the colour alone where it does not, and the bare URL in
    a pipe or a CI log — where an escape sequence would corrupt the output.
    """

    URL = "https://www.comet.com/docs/opik/mcp-server"

    @pytest.fixture
    def view(self):
        from opik.cli import install_view as rich_view

        return rich_view

    @staticmethod
    def _rendered(view, monkeypatch, **console_kwargs):
        import rich.console

        recorder = rich.console.Console(width=120, **console_kwargs)
        monkeypatch.setattr(view, "console", recorder)
        with recorder.capture() as capture:
            view.RichInstallView().problem(f"See {TestLinks.URL} for instructions.")
        return capture.get()

    def test_terminal__emits_an_osc8_hyperlink(self, view, monkeypatch):
        out = self._rendered(view, monkeypatch, force_terminal=True)

        assert "\x1b]8;" in out and self.URL in out

    def test_terminal__the_url_is_its_own_colour(self, view, monkeypatch):
        """Cyan against the yellow the rest of the message carries."""
        out = self._rendered(view, monkeypatch, force_terminal=True)

        assert "36m" in out, "cyan"
        assert "33m" in out, "the surrounding text keeps its yellow"

    def test_no_color_terminal__still_readable(self, view, monkeypatch):
        out = self._rendered(view, monkeypatch, force_terminal=True, no_color=True)

        assert self.URL in out

    def test_not_a_terminal__no_escapes_at_all(self, view, monkeypatch):
        """A pipe or a CI log must get the bare URL, still copy-pasteable."""
        out = self._rendered(view, monkeypatch, force_terminal=False)

        assert "\x1b" not in out
        assert self.URL in out

    def test_message_without_a_url__is_untouched(self, view, monkeypatch):
        import rich.console

        recorder = rich.console.Console(width=120, force_terminal=False)
        monkeypatch.setattr(view, "console", recorder)
        with recorder.capture() as capture:
            view.RichInstallView().problem("Nothing to click here.")

        assert capture.get().strip() == "Nothing to click here."

    def test_trailing_punctuation__stays_out_of_the_link(self, view, monkeypatch):
        """`See <url>.` must not make the full stop part of the address."""
        linked = view._linkify(f"See {self.URL}.")

        spans = [s for s in linked.spans if "link" in str(s.style)]
        assert spans and linked.plain[spans[0].start : spans[0].end] == self.URL
