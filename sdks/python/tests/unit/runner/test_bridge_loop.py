import threading
from typing import Dict, Optional
from unittest.mock import MagicMock


from opik.rest_api.core.api_error import ApiError
from opik.rest_api.types.bridge_command_batch_response import BridgeCommandBatchResponse
from opik.rest_api.types.bridge_command_item import BridgeCommandItem
from opik.runner.bridge_handlers import CommandError
from opik.runner.bridge_loop import BridgePollLoop


def _make_cmd(
    command_id: str = "cmd-1",
    cmd_type: str = "read_file",
    args: Optional[Dict] = None,
    timeout_seconds: int = 30,
) -> BridgeCommandItem:
    return BridgeCommandItem(
        command_id=command_id,
        type=cmd_type,
        args=args or {"path": "test.py"},
        timeout_seconds=timeout_seconds,
    )


def _make_batch(*cmds: BridgeCommandItem) -> BridgeCommandBatchResponse:
    return BridgeCommandBatchResponse(commands=list(cmds))


def _empty_batch() -> BridgeCommandBatchResponse:
    return BridgeCommandBatchResponse(commands=[])


class TestBridgePollLoopPolling:
    def test_poll__no_commands__continues_looping(self) -> None:
        api = MagicMock()
        shutdown = threading.Event()
        call_count = 0

        def poll_side_effect(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count >= 3:
                shutdown.set()
            return _empty_batch()

        api.runners.next_bridge_commands.side_effect = poll_side_effect

        loop = BridgePollLoop(api, "runner-1", {}, shutdown)
        loop.run()

        assert call_count >= 3

    def test_poll__single_command__dispatches_and_reports(self) -> None:
        api = MagicMock()
        shutdown = threading.Event()
        handler = MagicMock()
        handler.execute.return_value = {"content": "hello"}

        cmd = _make_cmd()
        call_count = 0

        def poll_side_effect(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return _make_batch(cmd)
            shutdown.set()
            return _empty_batch()

        api.runners.next_bridge_commands.side_effect = poll_side_effect

        loop = BridgePollLoop(api, "runner-1", {"read_file": handler}, shutdown)
        loop.run()

        handler.execute.assert_called_once_with({"path": "test.py"}, 30.0)
        api.runners.report_bridge_result.assert_called_once()
        report_call = api.runners.report_bridge_result.call_args
        assert report_call.args[1] == "cmd-1"
        assert report_call.kwargs["status"] == "completed"
        assert report_call.kwargs["result"] == {"content": "hello"}

    def test_poll__batch_of_commands__dispatches_all(self) -> None:
        api = MagicMock()
        shutdown = threading.Event()
        handler = MagicMock()
        handler.execute.return_value = {"ok": True}

        cmds = [_make_cmd(f"cmd-{i}") for i in range(3)]
        call_count = 0

        def poll_side_effect(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return _make_batch(*cmds)
            shutdown.set()
            return _empty_batch()

        api.runners.next_bridge_commands.side_effect = poll_side_effect

        loop = BridgePollLoop(api, "runner-1", {"read_file": handler}, shutdown)
        loop.run()

        assert handler.execute.call_count == 3
        assert api.runners.report_bridge_result.call_count == 3

    def test_poll__network_error__backs_off_and_retries(self) -> None:
        api = MagicMock()
        shutdown = threading.Event()
        call_count = 0

        def poll_side_effect(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count <= 2:
                raise ConnectionError("network down")
            shutdown.set()
            return _empty_batch()

        api.runners.next_bridge_commands.side_effect = poll_side_effect

        loop = BridgePollLoop(
            api,
            "runner-1",
            {},
            shutdown,
            initial_backoff_seconds=0.001,
            backoff_cap_seconds=0.01,
        )
        loop.run()

        assert call_count >= 3

    def test_poll__410_evicted__stops_loop(self) -> None:
        api = MagicMock()
        shutdown = threading.Event()
        api.runners.next_bridge_commands.side_effect = ApiError(
            status_code=410, body=None
        )

        loop = BridgePollLoop(api, "runner-1", {}, shutdown)
        loop.run()

        assert shutdown.is_set()

    def test_poll__shutdown_event__stops_loop(self) -> None:
        api = MagicMock()
        shutdown = threading.Event()
        call_count = 0

        def poll_side_effect(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count >= 1:
                shutdown.set()
            return _empty_batch()

        api.runners.next_bridge_commands.side_effect = poll_side_effect

        loop = BridgePollLoop(api, "runner-1", {}, shutdown)
        loop.run()

        assert shutdown.is_set()


class TestBridgePollLoopDispatch:
    def test_dispatch__handler_raises_error__reports_failed(self) -> None:
        api = MagicMock()
        shutdown = threading.Event()
        handler = MagicMock()
        handler.execute.side_effect = CommandError("file_not_found", "No such file")

        call_count = 0

        def poll_side_effect(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return _make_batch(_make_cmd())
            shutdown.set()
            return _empty_batch()

        api.runners.next_bridge_commands.side_effect = poll_side_effect

        loop = BridgePollLoop(api, "runner-1", {"read_file": handler}, shutdown)
        loop.run()

        report_call = api.runners.report_bridge_result.call_args
        assert report_call.kwargs["status"] == "failed"
        assert report_call.kwargs["error"]["code"] == "file_not_found"

    def test_dispatch__unknown_command_type__reports_failed(self) -> None:
        api = MagicMock()
        shutdown = threading.Event()

        call_count = 0

        def poll_side_effect(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return _make_batch(_make_cmd(cmd_type="unknown_cmd"))
            shutdown.set()
            return _empty_batch()

        api.runners.next_bridge_commands.side_effect = poll_side_effect

        loop = BridgePollLoop(api, "runner-1", {}, shutdown)
        loop.run()

        report_call = api.runners.report_bridge_result.call_args
        assert report_call.kwargs["status"] == "failed"
        assert report_call.kwargs["error"]["code"] == "unknown_type"


class TestBridgePollLoopReporting:
    def test_report__success__calls_api_with_result(self) -> None:
        api = MagicMock()
        shutdown = threading.Event()
        handler = MagicMock()
        handler.execute.return_value = {"content": "data"}

        call_count = 0

        def poll_side_effect(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return _make_batch(_make_cmd())
            shutdown.set()
            return _empty_batch()

        api.runners.next_bridge_commands.side_effect = poll_side_effect

        loop = BridgePollLoop(api, "runner-1", {"read_file": handler}, shutdown)
        loop.run()

        api.runners.report_bridge_result.assert_called_once()
        kw = api.runners.report_bridge_result.call_args.kwargs
        assert kw["status"] == "completed"
        assert kw["result"] == {"content": "data"}
        assert isinstance(kw["duration_ms"], int)

    def test_report__network_error__retries_successfully(self) -> None:
        api = MagicMock()
        shutdown = threading.Event()
        handler = MagicMock()
        handler.execute.return_value = {"ok": True}

        report_call_count = 0

        def report_side_effect(*args, **kwargs):
            nonlocal report_call_count
            report_call_count += 1
            if report_call_count == 1:
                raise ConnectionError("network")

        api.runners.report_bridge_result.side_effect = report_side_effect

        call_count = 0

        def poll_side_effect(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return _make_batch(_make_cmd())
            shutdown.set()
            return _empty_batch()

        api.runners.next_bridge_commands.side_effect = poll_side_effect

        loop = BridgePollLoop(api, "runner-1", {"read_file": handler}, shutdown)
        loop.run()

        assert report_call_count == 2

    def test_report__all_retries_fail__logs_and_continues(self) -> None:
        api = MagicMock()
        shutdown = threading.Event()
        handler = MagicMock()
        handler.execute.return_value = {"ok": True}

        api.runners.report_bridge_result.side_effect = ConnectionError("always fails")

        call_count = 0

        def poll_side_effect(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return _make_batch(_make_cmd())
            shutdown.set()
            return _empty_batch()

        api.runners.next_bridge_commands.side_effect = poll_side_effect

        loop = BridgePollLoop(api, "runner-1", {"read_file": handler}, shutdown)
        loop.run()

        assert api.runners.report_bridge_result.call_count == 3

    def test_report__409_duplicate__swallows_error(self) -> None:
        api = MagicMock()
        shutdown = threading.Event()
        handler = MagicMock()
        handler.execute.return_value = {"ok": True}

        api.runners.report_bridge_result.side_effect = ApiError(
            status_code=409, body=None
        )

        call_count = 0

        def poll_side_effect(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return _make_batch(_make_cmd())
            shutdown.set()
            return _empty_batch()

        api.runners.next_bridge_commands.side_effect = poll_side_effect

        loop = BridgePollLoop(api, "runner-1", {"read_file": handler}, shutdown)
        loop.run()

        # 409 should be swallowed, not retried
        assert api.runners.report_bridge_result.call_count == 1
