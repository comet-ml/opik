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

    def test_done__marks_completion_without_restating_the_results(self, view):
        """The rows above already list each component and each client, with marks.

        Summarising them again here put "MCP server and skill pack for Cursor"
        directly under the two rows that had just said exactly that.
        """
        with view.console.capture() as capture:
            view.RichInstallView().done(
                ["MCP server", "skill pack"], ["Cursor", "Claude Code", "Codex"]
            )

        out = capture.get()
        assert "Done" in out
        assert "MCP server" not in out
        assert "Cursor" not in out

    def test_done__says_nothing_about_what_to_do_next(self, view):
        """The ending below it does that, in words that fit the ending reached.

        This block said "restart it, then ask to list my Opik projects" directly
        above an offer to start the client on a different question.
        """
        with view.console.capture() as capture:
            view.RichInstallView().done(["MCP server"], ["Cursor"])

        out = capture.get()
        assert "Restart" not in out
        assert "list my Opik projects" not in out

    def test_done__sign_in_needed__is_the_one_thing_it_still_says(self, view):
        installer = view.RichInstallView()
        installer.plan("Opik Cloud", "Hosted server", [], needs_sign_in=True)
        with view.console.capture() as capture:
            installer.done(["MCP server"], ["Claude Code"])

        out = capture.get()
        assert "Signing in" in out
        assert out.index("Done") < out.index("Signing in")

    def test_restart_note__names_the_prompt_in_green(self, view, monkeypatch):
        """The one thing the user is meant to copy, so it stands out."""
        import rich.console

        recorder = rich.console.Console(force_terminal=True, width=100)
        monkeypatch.setattr(view, "console", recorder)

        with recorder.capture() as capture:
            view.render_restart_note(mcp_installed=True)

        assert '\x1b[32m"list my Opik projects via Opik MCP"' in capture.get()

    def test_restart_note__without_mcp__does_not_name_a_server_that_is_not_there(
        self, view
    ):
        """`--install-skills --no-install-mcp` reaches this ending too."""
        with view.console.capture() as capture:
            view.render_restart_note(mcp_installed=False)

        out = capture.get()
        assert "skill pack" in out
        assert "Opik MCP" not in out

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
            "pick", [mcp_view.HostChoice("cursor", "Cursor")]
        )

        assert chosen == ["cursor"]

    def test_logging_view__single_candidate_declined(self, monkeypatch):
        monkeypatch.setattr("builtins.input", lambda prompt: "n")

        chosen = mcp_view.LoggingInstallView().choose_hosts(
            "pick", [mcp_view.HostChoice("cursor", "Cursor")]
        )

        assert chosen == []

    def test_logging_view__menu_lists_every_candidate(self, monkeypatch):
        prompts = []

        def fake_input(prompt):
            prompts.append(prompt)
            return "5"  # Skip (3 hosts -> 4 not listed, 5 skip)

        monkeypatch.setattr("builtins.input", fake_input)

        mcp_view.LoggingInstallView().choose_hosts("pick", self._candidates())

        assert "Claude Code" in prompts[0]

    def test_logging_view__one_client_like_the_picker_it_stands_in_for(
        self, monkeypatch
    ):
        """No "All of the above", and `1,3` is not an answer.

        Both registered servers this flow could not then finish for: it ends by
        starting the one client that was chosen.
        """
        prompts = []

        def fake_input(prompt):
            prompts.append(prompt)
            return "1,3" if len(prompts) == 1 else "2"

        monkeypatch.setattr("builtins.input", fake_input)

        chosen = mcp_view.LoggingInstallView().choose_hosts("pick", self._candidates())

        assert "All of the above" not in prompts[0]
        assert "commas" not in prompts[0]
        # The comma answer was refused rather than taken, so the menu came back.
        assert len(prompts) == 2
        assert chosen == ["cursor"]

    def test_logging_view__skip(self, monkeypatch):
        """3 candidates, so 4 is "not listed" and 5 is Skip."""
        monkeypatch.setattr("builtins.input", lambda prompt: "5")

        assert (
            mcp_view.LoggingInstallView().choose_hosts("pick", self._candidates()) == []
        )

    def test_logging_view__client_not_listed(self, monkeypatch):
        monkeypatch.setattr("builtins.input", lambda prompt: "4")

        assert mcp_view.LoggingInstallView().choose_hosts(
            "pick", self._candidates()
        ) == [mcp_view.MANUAL_SETUP]

    def test_logging_view__invalid_then_valid__retries(self, monkeypatch):
        monkeypatch.setattr("builtins.input", mock.Mock(side_effect=["x", "99", "2"]))

        chosen = mcp_view.LoggingInstallView().choose_hosts("pick", self._candidates())

        assert chosen == ["cursor"]

    def test_rich_view__uses_the_picker_when_the_terminal_allows(self, monkeypatch):
        from opik.cli import install_view as rich_view
        from opik.cli import selector

        monkeypatch.setattr(selector, "is_supported", lambda: True)
        monkeypatch.setattr(selector, "choose_one", lambda **kwargs: "codex")

        chosen = rich_view.RichInstallView().choose_hosts("pick", self._candidates())

        assert chosen == ["codex"]

    def test_rich_view__no_picker_support__falls_back_to_the_menu(self, monkeypatch):
        from opik.cli import install_view as rich_view
        from opik.cli import selector

        monkeypatch.setattr(selector, "is_supported", lambda: False)
        monkeypatch.setattr("builtins.input", lambda prompt: "2")

        chosen = rich_view.RichInstallView().choose_hosts("pick", self._candidates())

        assert chosen == ["cursor"]

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
            "choose_one",
            lambda **kwargs: offered.update(kwargs) or mcp_view.MANUAL_SETUP,
        )

        chosen = rich_view.RichInstallView().choose_hosts(
            "pick", [mcp_view.HostChoice("cursor", "Cursor")]
        )

        assert chosen == [mcp_view.MANUAL_SETUP]
        labels = [choice.label for choice in offered["choices"]]
        assert labels == ["Cursor", mcp_view.MANUAL_SETUP_LABEL]

    def test_rich_view__offers_no_all_row(self, monkeypatch):
        """One client, so there is nothing for an "All" row to stand in for."""
        from opik.cli import install_view as rich_view
        from opik.cli import selector

        monkeypatch.setattr(selector, "is_supported", lambda: True)
        offered = {}
        monkeypatch.setattr(
            selector,
            "choose_one",
            lambda **kwargs: offered.update(kwargs) or "cursor",
        )

        rich_view.RichInstallView().choose_hosts("pick", self._candidates())

        assert "All" not in [choice.label for choice in offered["choices"]]

    def test_rich_view__cancelled_picker__propagates_none(self, monkeypatch):
        from opik.cli import install_view as rich_view
        from opik.cli import selector

        monkeypatch.setattr(selector, "is_supported", lambda: True)
        monkeypatch.setattr(selector, "choose_one", lambda **kwargs: None)

        assert (
            rich_view.RichInstallView().choose_hosts("pick", self._candidates()) is None
        )


class TestThePickerRows:
    """One client, and a way out for the user whose client is not listed.

    The flow ends by starting the chosen client with a prompt, which only means
    anything for one of them, so the picker takes one answer. `--ai-client` is
    still repeatable for scripted runs, and those skip this picker entirely.
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
        monkeypatch.setattr(selector, "choose_one", fake)
        chosen = rich_view.RichInstallView().choose_hosts("pick", self._candidates())
        return chosen, seen["choices"]

    def test_clients_first_then_not_listed(self, monkeypatch):
        _, choices = self._choose(monkeypatch, "codex")

        assert [c.label for c in choices] == [
            "Claude Code",
            "Codex",
            "Cursor",
            mcp_view.MANUAL_SETUP_LABEL,
        ]

    def test_no_skip_row(self, monkeypatch):
        """Escape is the silent decline; the extra row is the one with an answer."""
        _, choices = self._choose(monkeypatch, "codex")

        assert "Skip" not in [c.label for c in choices]

    def test_not_listed__returns_the_sentinel(self, monkeypatch):
        """It must not reach the installer as a host key, or nothing installs."""
        chosen, _ = self._choose(monkeypatch, mcp_view.MANUAL_SETUP)

        assert chosen == [mcp_view.MANUAL_SETUP]

    def test_a_chosen_client__comes_back_alone(self, monkeypatch):
        chosen, _ = self._choose(monkeypatch, "codex")

        assert chosen == ["codex"]

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
        linked = view._emphasize(f"See {self.URL}.")

        spans = [s for s in linked.spans if "link" in str(s.style)]
        assert spans and linked.plain[spans[0].start : spans[0].end] == self.URL


@pytest.fixture
def terminal(monkeypatch):
    """A recorder that keeps the escapes, so styling can be asserted."""
    import rich.console

    from opik.cli import install_view as rich_view

    recorder = rich.console.Console(force_terminal=True, width=100)
    monkeypatch.setattr(rich_view, "console", recorder)
    return rich_view, recorder


class TestWhatToActOnStandsOut:
    """Links and commands are what the user acts on, so they leave the grey behind."""

    @staticmethod
    def _styles_at(rendered, fragment):
        start = rendered.plain.index(fragment)
        return [
            str(span.style) for span in rendered.spans if span.start <= start < span.end
        ]

    def test_a_command_in_backticks__is_set_in_full_weight(self):
        from opik.cli import install_view as rich_view

        rendered = rich_view._emphasize(
            "Run `claude mcp login opik-mcp` to finish it.", base="dim"
        )

        assert rich_view._CODE_STYLE in self._styles_at(rendered, "claude mcp login")

    def test_an_indented_command_line__is_set_in_full_weight(self):
        from opik.cli import install_view as rich_view

        rendered = rich_view._emphasize(
            "Name one directly:\n    opik mcp configure --ai-client cursor",
            base="yellow",
        )

        assert rich_view._CODE_STYLE in self._styles_at(rendered, "opik mcp configure")
        assert self._styles_at(rendered, "Name one") == []

    def test_notes__carry_the_emphasis_too(self, terminal):
        """They were plain grey text, so a command in one looked like prose."""
        rich_view, recorder = terminal

        with recorder.capture() as capture:
            rich_view.RichInstallView().note("Run `uv tool install opik-mcp==0.2.13`.")

        # Bold in the terminal's own colour, and out of the dim around it.
        assert "\x1b[1;39m`uv tool install" in capture.get()


class TestTheConfigureEnding:
    @staticmethod
    def _configured(**overrides):
        from opik.configurator import configure as opik_configure

        fields = dict(
            saved=True,
            config_file=str(pathlib.Path.home() / ".opik.config"),
            url=None,
            workspace="acme-ai",
            project_name="checkout-bot",
        )
        fields.update(overrides)
        return opik_configure.Configured(**fields)

    def test_says_what_was_set_up_row_by_row(self, terminal):
        rich_view, recorder = terminal

        with recorder.capture() as capture:
            rich_view.render_configured(
                self._configured(),
                project_url="https://www.comet.com/opik/acme-ai/projects/0190-abc/",
                project_exists=True,
            )

        out = capture.get()
        assert "Opik is configured" in out
        assert "~/.opik.config" in out
        assert "acme-ai" in out
        assert "checkout-bot" in out
        assert rich_view.PROJECT_NAME_DOCS_URL in out

    def test_nothing_rewritten__says_it_was_already_configured(self, terminal):
        rich_view, recorder = terminal

        with recorder.capture() as capture:
            rich_view.render_configured(
                self._configured(saved=False),
                project_url="https://www.comet.com/opik/acme-ai/projects/0190-abc/",
                project_exists=True,
            )

        assert "Opik is already configured" in capture.get()

    def test_a_non_cloud_deployment__names_its_url(self, terminal):
        rich_view, recorder = terminal

        with recorder.capture() as capture:
            rich_view.render_configured(
                self._configured(workspace="default", url="http://localhost:5173/"),
                project_url="http://localhost:5173/default/projects",
                project_exists=False,
            )

        out = capture.get()
        assert "default" in out
        assert "localhost:5173" in out

    def test_credentials_in_the_url__are_not_shown_or_linked(self, terminal):
        """The summary prints the URL and makes it a link; a password must be in neither."""
        rich_view, recorder = terminal

        with recorder.capture() as capture:
            rich_view.render_configured(
                self._configured(url="https://alice:s3cret@opik.acme.io/"),
                project_url="https://alice:s3cret@opik.acme.io/default/projects",
                project_exists=False,
            )

        out = capture.get()
        assert "s3cret" not in out
        assert "alice" not in out
        assert "https://opik.acme.io/" in out


class TestTheSuggestedPrompt:
    """The prompt is what the user sends or pastes, so it is not grey."""

    PROMPT = "Using the Opik /opik-diagnose skill, give me an overview."

    def test_offer__heads_it_and_sets_it_in_full_weight(self, terminal):
        rich_view, recorder = terminal

        with recorder.capture() as capture:
            rich_view.render_handoff_offer(self.PROMPT)

        out = capture.get()
        assert "Suggested first prompt" in out
        assert "\x1b[1m" + self.PROMPT in out
        assert "\x1b[2m" + self.PROMPT not in out

    def test_paste_ending__uses_the_same_block_then_says_what_to_do(self, terminal):
        rich_view, recorder = terminal

        with recorder.capture() as capture:
            rich_view.render_prompt_to_paste("Cursor", self.PROMPT)

        out = capture.get()
        assert out.index("Suggested first prompt") < out.index("Restart")
        assert "paste the prompt above" in out


class TestTheConfigureEndingLinksTheProject:
    def test_an_existing_project__is_linked_directly(self, terminal):
        rich_view, recorder = terminal

        with recorder.capture() as capture:
            rich_view.render_configured(
                TestTheConfigureEnding._configured(),
                project_url="https://www.comet.com/opik/acme-ai/projects/0190-abc/",
                project_exists=True,
            )

        out = capture.get()
        assert "https://www.comet.com/opik/acme-ai/projects/0190-abc/" in out
        assert "after its first trace" not in out

    def test_a_project_not_created_yet__links_the_list_and_says_why(self, terminal):
        """A project is created by its first trace, so a fresh setup has none."""
        rich_view, recorder = terminal

        with recorder.capture() as capture:
            rich_view.render_configured(
                TestTheConfigureEnding._configured(),
                project_url="https://www.comet.com/opik/acme-ai/projects",
                project_exists=False,
            )

        out = capture.get()
        assert "https://www.comet.com/opik/acme-ai/projects" in out
        assert "after its first trace" in out


class TestTheEndingAfterAFailedSignIn:
    def test_ends_on_the_step_left__not_on_done(self, terminal):
        rich_view, recorder = terminal
        view = rich_view.RichInstallView()
        view.sign_in_failed(["Claude Code"])

        with recorder.capture() as capture:
            view.done(["MCP server"], ["Claude Code"])

        out = capture.get()
        assert "not signed in yet" in out
        assert "claude mcp login opik-mcp" in out
        assert "Done" not in out
