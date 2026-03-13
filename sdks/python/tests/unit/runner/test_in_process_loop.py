import asyncio
import threading
import time
from unittest.mock import MagicMock

import pytest

from opik.rest_api.core.api_error import ApiError
from opik.rest_api.types.local_runner_heartbeat_response import (
    LocalRunnerHeartbeatResponse,
)
from opik.rest_api.types.local_runner_job import LocalRunnerJob
from opik.runner import in_process_loop, registry


@pytest.fixture
def mock_api():
    api = MagicMock()
    api.runners.heartbeat = MagicMock(
        return_value=LocalRunnerHeartbeatResponse(cancelled_job_ids=[])
    )
    api.runners.next_job = MagicMock(return_value=None)
    api.runners.report_job_result = MagicMock()
    api.runners.register_agents = MagicMock()
    return api


@pytest.fixture
def shutdown_event():
    return threading.Event()


@pytest.fixture
def loop(mock_api, shutdown_event):
    return in_process_loop.InProcessRunnerLoop(
        mock_api,
        "r-1",
        shutdown_event,
        heartbeat_interval_seconds=100,
    )


class TestHeartbeatLoop:
    def test_heartbeat__410__triggers_shutdown(self, mock_api, shutdown_event, loop):
        loop._heartbeat_interval_seconds = 0.05
        mock_api.runners.heartbeat.side_effect = ApiError(status_code=410)

        t = threading.Thread(target=loop._heartbeat_loop)
        t.start()
        t.join(timeout=5)

        assert shutdown_event.is_set()

    def test_heartbeat__cancelled_ids__tracked(self, mock_api, shutdown_event, loop):
        loop._heartbeat_interval_seconds = 0.05
        call_count = 0

        def heartbeat_side_effect(runner_id):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return LocalRunnerHeartbeatResponse(cancelled_job_ids=["j-1"])
            shutdown_event.set()
            return LocalRunnerHeartbeatResponse(cancelled_job_ids=[])

        mock_api.runners.heartbeat.side_effect = heartbeat_side_effect

        t = threading.Thread(target=loop._heartbeat_loop)
        t.start()
        t.join(timeout=5)

        assert "j-1" in loop._cancelled_jobs


class TestPollLoop:
    def test_poll__no_jobs__polls_repeatedly(self, mock_api, shutdown_event, loop):
        call_count = 0

        def side_effect(runner_id):
            nonlocal call_count
            call_count += 1
            if call_count >= 3:
                shutdown_event.set()
            raise ApiError(status_code=204)

        mock_api.runners.next_job.side_effect = side_effect
        loop._loop = asyncio.new_event_loop()

        t = threading.Thread(target=loop._poll_loop)
        t.start()
        t.join(timeout=5)

        assert call_count >= 3

    def test_poll__error__backs_off(self, mock_api, shutdown_event):
        lp = in_process_loop.InProcessRunnerLoop(
            mock_api,
            "r-1",
            shutdown_event,
            heartbeat_interval_seconds=100,
            backoff_cap_seconds=0.01,
        )
        lp._loop = asyncio.new_event_loop()
        call_count = 0

        def side_effect(runner_id):
            nonlocal call_count
            call_count += 1
            if call_count >= 2:
                shutdown_event.set()
                return None
            raise ConnectionError("fail")

        mock_api.runners.next_job.side_effect = side_effect

        t = threading.Thread(target=lp._poll_loop)
        t.start()
        t.join(timeout=5)

        assert call_count >= 2

    def test_poll__job_available__queued(self, mock_api, shutdown_event, loop):
        job = LocalRunnerJob(id="j-1", agent_name="test", inputs={"q": "hi"})
        call_count = 0

        def side_effect(runner_id):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return job
            shutdown_event.set()
            raise ApiError(status_code=204)

        mock_api.runners.next_job.side_effect = side_effect

        aio_loop = asyncio.new_event_loop()
        loop._loop = aio_loop

        calls = []

        def mock_call_soon(fn, *args):
            calls.append(args)
            fn(*args)

        loop._loop.call_soon_threadsafe = mock_call_soon

        t = threading.Thread(target=loop._poll_loop)
        t.start()
        t.join(timeout=5)

        assert len(calls) >= 1
        assert calls[0][0].id == "j-1"


class TestJobExecution:
    def test_execute_job__sync_entrypoint__calls_function(
        self, mock_api, shutdown_event
    ):
        def my_agent(q):
            return f"answer: {q}"

        registry.register("my_agent", my_agent, "proj", [], "")

        lp = in_process_loop.InProcessRunnerLoop(
            mock_api,
            "r-1",
            shutdown_event,
        )

        job = LocalRunnerJob(id="j-1", agent_name="my_agent", inputs={"q": "hello"})

        loop = asyncio.new_event_loop()
        loop.run_until_complete(lp._execute_job(job))
        loop.close()

        mock_api.runners.report_job_result.assert_called_once()
        call_kwargs = mock_api.runners.report_job_result.call_args[1]
        assert call_kwargs["status"] == "completed"

    def test_execute_job__async_entrypoint__calls_function(
        self, mock_api, shutdown_event
    ):
        async def my_agent(q):
            return f"answer: {q}"

        registry.register("my_agent", my_agent, "proj", [], "")

        lp = in_process_loop.InProcessRunnerLoop(
            mock_api,
            "r-1",
            shutdown_event,
        )

        job = LocalRunnerJob(id="j-1", agent_name="my_agent", inputs={"q": "hello"})

        loop = asyncio.new_event_loop()
        loop.run_until_complete(lp._execute_job(job))
        loop.close()

        call_kwargs = mock_api.runners.report_job_result.call_args[1]
        assert call_kwargs["status"] == "completed"

    def test_execute_job__unknown_agent__reports_failed(self, mock_api, shutdown_event):
        lp = in_process_loop.InProcessRunnerLoop(
            mock_api,
            "r-1",
            shutdown_event,
        )

        job = LocalRunnerJob(id="j-1", agent_name="missing", inputs={})

        loop = asyncio.new_event_loop()
        loop.run_until_complete(lp._execute_job(job))
        loop.close()

        call_kwargs = mock_api.runners.report_job_result.call_args[1]
        assert call_kwargs["status"] == "failed"
        assert "Unknown agent" in call_kwargs["error"]

    def test_execute_job__exception__reports_failed(self, mock_api, shutdown_event):
        def bad_agent():
            raise ValueError("boom")

        registry.register("bad", bad_agent, "proj", [], "")

        lp = in_process_loop.InProcessRunnerLoop(
            mock_api,
            "r-1",
            shutdown_event,
        )

        job = LocalRunnerJob(id="j-1", agent_name="bad", inputs={})

        loop = asyncio.new_event_loop()
        loop.run_until_complete(lp._execute_job(job))
        loop.close()

        call_kwargs = mock_api.runners.report_job_result.call_args[1]
        assert call_kwargs["status"] == "failed"
        assert "ValueError" in call_kwargs["error"]

    def test_execute_job__timeout__reports_failed(self, mock_api, shutdown_event):
        def slow_agent():
            time.sleep(5)

        registry.register("slow", slow_agent, "proj", [], "")

        lp = in_process_loop.InProcessRunnerLoop(
            mock_api,
            "r-1",
            shutdown_event,
        )

        job = LocalRunnerJob(id="j-1", agent_name="slow", inputs={}, timeout=1)

        loop = asyncio.new_event_loop()
        loop.run_until_complete(lp._execute_job(job))
        loop.close()

        call_kwargs = mock_api.runners.report_job_result.call_args[1]
        assert call_kwargs["status"] == "failed"
        assert "timed out" in call_kwargs["error"].lower()

    def test_execute_job__cancelled__skipped(self, mock_api, shutdown_event):
        def my_agent():
            return "ok"

        registry.register("my_agent", my_agent, "proj", [], "")

        lp = in_process_loop.InProcessRunnerLoop(
            mock_api,
            "r-1",
            shutdown_event,
        )
        lp._cancelled_jobs["j-cancel"] = 0.0

        job = LocalRunnerJob(id="j-cancel", agent_name="my_agent", inputs={})

        loop = asyncio.new_event_loop()
        loop.run_until_complete(lp._execute_job(job))
        loop.close()

        mock_api.runners.report_job_result.assert_not_called()

    def test_execute_job__trace_id__merges_opik_args(self, mock_api, shutdown_event):
        captured_kwargs = {}

        def my_agent(**kwargs):
            captured_kwargs.update(kwargs)
            return "ok"

        registry.register("my_agent", my_agent, "proj", [], "")

        lp = in_process_loop.InProcessRunnerLoop(
            mock_api,
            "r-1",
            shutdown_event,
        )

        job = LocalRunnerJob(
            id="j-1",
            agent_name="my_agent",
            inputs={
                "opik_args": {
                    "trace": {"tags": ["existing"]},
                    "span": {"metadata": {"k": "v"}},
                }
            },
            trace_id="t-123",
        )

        loop = asyncio.new_event_loop()
        loop.run_until_complete(lp._execute_job(job))
        loop.close()

        assert captured_kwargs["opik_args"]["trace"]["id"] == "t-123"
        assert captured_kwargs["opik_args"]["trace"]["tags"] == ["existing"]
        assert captured_kwargs["opik_args"]["span"]["metadata"] == {"k": "v"}

    def test_execute_job__report_failure__does_not_raise(
        self, mock_api, shutdown_event
    ):
        def my_agent():
            return "ok"

        registry.register("my_agent", my_agent, "proj", [], "")

        mock_api.runners.report_job_result.side_effect = RuntimeError("network down")

        lp = in_process_loop.InProcessRunnerLoop(
            mock_api,
            "r-1",
            shutdown_event,
        )

        job = LocalRunnerJob(id="j-1", agent_name="my_agent", inputs={})

        loop = asyncio.new_event_loop()
        loop.run_until_complete(lp._execute_job(job))
        loop.close()

        mock_api.runners.report_job_result.assert_called_once()
