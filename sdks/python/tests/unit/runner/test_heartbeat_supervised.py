import threading
from unittest.mock import MagicMock, patch

from opik.runner.in_process_loop import InProcessRunnerLoop


class TestHeartbeatSupervised:
    @patch.dict("os.environ", {"OPIK_SUPERVISED": "true"})
    def test_supervised__skips_heartbeat_thread(self) -> None:
        api = MagicMock()
        shutdown = threading.Event()
        shutdown.set()

        loop = InProcessRunnerLoop(api, "runner-1", shutdown)

        started_threads: list[str] = []
        original_thread = threading.Thread

        def tracking_thread(*args, **kwargs):
            t = original_thread(*args, **kwargs)
            target = kwargs.get("target") or (args[0] if args else None)
            if target and hasattr(target, "__name__"):
                started_threads.append(target.__name__)
            elif target and hasattr(target, "__func__"):
                started_threads.append(target.__func__.__name__)
            return t

        with patch(
            "opik.runner.in_process_loop.threading.Thread",
            side_effect=tracking_thread,
        ):
            loop.run()

        assert "_heartbeat_loop" not in started_threads

    @patch.dict("os.environ", {}, clear=False)
    def test_unsupervised__starts_heartbeat_thread(self) -> None:
        import os

        os.environ.pop("OPIK_SUPERVISED", None)

        api = MagicMock()
        shutdown = threading.Event()
        shutdown.set()

        loop = InProcessRunnerLoop(api, "runner-1", shutdown)

        started_targets: list = []
        original_thread_init = threading.Thread.__init__

        def tracking_init(self_thread, *args, **kwargs):
            original_thread_init(self_thread, *args, **kwargs)
            target = kwargs.get("target")
            if target:
                started_targets.append(target)

        with patch.object(threading.Thread, "__init__", tracking_init):
            loop.run()

        target_names = [
            getattr(t, "__func__", t).__name__
            if hasattr(getattr(t, "__func__", t), "__name__")
            else str(t)
            for t in started_targets
        ]
        assert "_heartbeat_loop" in target_names
