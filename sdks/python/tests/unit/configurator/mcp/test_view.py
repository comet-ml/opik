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

    def test_next_steps__no_assistants__still_reads(self, logger):
        mcp_view.LoggingInstallView().next_steps([])

        assert "your AI host" in str(logger.info.call_args)


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
        from opik.cli import mcp_view as rich_view

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

    def test_next_steps__joins_names_readably(self, view):
        with view.console.capture() as capture:
            view.RichInstallView().next_steps(["Cursor", "Claude Code", "Codex"])

        assert "Cursor, Claude Code and Codex" in capture.get()

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
