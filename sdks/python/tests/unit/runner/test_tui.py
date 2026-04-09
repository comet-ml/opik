from unittest.mock import MagicMock

from rich.console import Console

from opik.runner.tui import RunnerTUI


def _make_tui() -> RunnerTUI:
    console = MagicMock(spec=Console)
    console.is_terminal = False
    return RunnerTUI(console=console)


class TestError:
    def test_prints_error_message(self) -> None:
        tui = _make_tui()
        tui.error("something went wrong")
        tui._console.print.assert_called_once()
        rendered = tui._console.print.call_args[0][0]
        assert "something went wrong" in rendered.plain

    def test_crash_loop_message(self) -> None:
        tui = _make_tui()
        tui.error("Crash loop detected — waiting for file change to retry")
        rendered = tui._console.print.call_args[0][0]
        assert "Crash loop" in rendered.plain


class TestChildRestarted:
    def test_includes_reason(self) -> None:
        tui = _make_tui()
        tui.child_restarted("agent process has failed")
        rendered = tui._console.print.call_args[0][0]
        assert "Restarting: agent process has failed" in rendered.plain

    def test_clears_pending_ops(self) -> None:
        tui = _make_tui()
        tui._pending_ops["cmd-1"] = MagicMock()
        tui.child_restarted("file changed")
        assert len(tui._pending_ops) == 0
