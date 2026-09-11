import threading
import time
from unittest.mock import MagicMock, patch

from opik.rest_api.core.api_error import ApiError
from opik.rest_api.types.local_runner_heartbeat_response import (
    LocalRunnerHeartbeatResponse,
)
from opik.runner.in_process_loop import InProcessRunnerLoop


class TestInProcessHeartbeat:
    def test_run__always_starts_heartbeat_thread(self) -> None:
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

    def test_heartbeat__sends_capabilities(self) -> None:
        api = MagicMock()
        api.runners.heartbeat.return_value = LocalRunnerHeartbeatResponse()
        shutdown = threading.Event()

        loop = InProcessRunnerLoop(api, "runner-1", shutdown)

        t = threading.Thread(target=loop._heartbeat_loop, daemon=True)
        t.start()
        time.sleep(0.3)
        shutdown.set()
        t.join(timeout=5)

        api.runners.heartbeat.assert_called()
        call_kwargs = api.runners.heartbeat.call_args.kwargs
        assert call_kwargs["capabilities"] == ["jobs", "bridge"]

    def test_heartbeat__410__shuts_down(self) -> None:
        api = MagicMock()
        api.runners.heartbeat.side_effect = ApiError(status_code=410, body=None)
        shutdown = threading.Event()

        loop = InProcessRunnerLoop(api, "runner-1", shutdown)

        t = threading.Thread(target=loop._heartbeat_loop, daemon=True)
        t.start()
        t.join(timeout=5)

        assert shutdown.is_set()
