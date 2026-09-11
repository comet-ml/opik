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
