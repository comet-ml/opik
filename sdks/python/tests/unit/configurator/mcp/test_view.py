import pathlib
from unittest import mock

import pytest

from opik.configurator.mcp import view as mcp_view


def _targets():
    return [
        mcp_view.PlannedTarget("Cursor", "~/.cursor/mcp.json"),
        mcp_view.PlannedTarget("Claude Code", "via `claude mcp add`"),
    ]


class TestLoggingInstallView:
    """The default view keeps `opik.configure()` a well-behaved library call."""

    @pytest.fixture
    def logger(self, monkeypatch):
        # Opik's logging setup disables propagation, so caplog sees nothing;
        # asserting on the logger itself is both reliable and more precise.
        spy = mock.Mock()
        monkeypatch.setattr(mcp_view, "LOGGER", spy)
        return spy

    def test_plan__goes_to_the_logger_not_stdout(self, logger, capsys):
        mcp_view.LoggingInstallView().plan("Opik Cloud", "Local server", _targets())

        assert "Cursor" in str(logger.info.call_args)
        # A library must not paint on the caller's stdout.
        assert capsys.readouterr().out == ""

    def test_step__logs_and_yields(self, logger):
        entered = False
        with mcp_view.LoggingInstallView().step("Checking the connection"):
            entered = True

        assert entered
        assert "Checking the connection" in str(logger.info.call_args)

    def test_step__propagates_exceptions(self, logger):
        """A spinner must never swallow the failure it was covering."""
        with pytest.raises(ValueError):
            with mcp_view.LoggingInstallView().step("probing"):
                raise ValueError("boom")

    def test_results__success_is_info_and_failure_is_warning(self, logger):
        mcp_view.LoggingInstallView().results(
            [
                mcp_view.TargetResult("Cursor", "Added 'opik-mcp'", True, "Added"),
                mcp_view.TargetResult("Codex", "no codex CLI", False),
            ]
        )

        assert logger.info.call_count == 1
        assert "no codex CLI" in str(logger.warning.call_args)

    def test_verification__failure_is_a_warning(self, logger):
        mcp_view.LoggingInstallView().verification(False, "HTTP 401")

        logger.info.assert_not_called()
        assert "HTTP 401" in str(logger.warning.call_args)

    def test_verification__success_is_info(self, logger):
        mcp_view.LoggingInstallView().verification(True, "7 projects visible")

        logger.warning.assert_not_called()
        assert "7 projects visible" in str(logger.info.call_args)

    def test_done__no_assistants__still_reads(self, logger):
        mcp_view.LoggingInstallView().done(["MCP server"], [])

        assert "your AI client" in str(logger.info.call_args)

    def test_done__names_what_was_set_up(self, logger):
        mcp_view.LoggingInstallView().done(["MCP server", "skill pack"], ["Cursor"])

        logged = str(logger.info.call_args)
        assert "MCP server" in logged
        assert "skill pack" in logged
        assert "Cursor" in logged


class TestTargetResult:
    def test_short__prefers_the_summary(self):
        result = mcp_view.TargetResult(
            "Cursor", "Added 'opik-mcp' in /long/path", True, "Added"
        )
        assert result.short == "Added"

    def test_short__falls_back_to_detail(self):
        result = mcp_view.TargetResult("Codex", "no codex CLI on PATH", False)
        assert result.short == "no codex CLI on PATH"


class TestDisplayPath:
    def test_display_path__collapses_home(self, monkeypatch, tmp_path):
        monkeypatch.setattr(pathlib.Path, "home", classmethod(lambda cls: tmp_path))
        assert mcp_view.display_path(tmp_path / ".cursor" / "mcp.json") == (
            "~/.cursor/mcp.json"
        )

    def test_display_path__outside_home__is_left_alone(self, monkeypatch, tmp_path):
        monkeypatch.setattr(pathlib.Path, "home", classmethod(lambda cls: tmp_path))
        assert mcp_view.display_path(pathlib.Path("/etc/opik.json")) == "/etc/opik.json"


class TestDefaultView:
    def test_default_view__is_the_logging_one(self):
        assert isinstance(mcp_view.default_view(), mcp_view.LoggingInstallView)

    def test_default_view__is_reused(self):
        assert mcp_view.default_view() is mcp_view.default_view()


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
