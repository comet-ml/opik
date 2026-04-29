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
        poll_idle_interval_seconds=0.01,
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

        def heartbeat_side_effect(runner_id, **kwargs):
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
            return None

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
            return None

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
        def my_agent(q, **kwargs):
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

    def test_execute_job__async_entrypoint__calls_function(
        self, mock_api, shutdown_event
    ):
        async def my_agent(q, **kwargs):
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
        def bad_agent(**kwargs):
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
        def slow_agent(**kwargs):
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
        def my_agent(**kwargs):
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
        )

        loop = asyncio.new_event_loop()
        loop.run_until_complete(lp._execute_job(job))
        loop.close()

        assert len(captured_kwargs["opik_args"]["trace"]["id"]) > 0  # injected trace ID
        assert captured_kwargs["opik_args"]["trace"]["tags"] == ["existing"]
        assert captured_kwargs["opik_args"]["span"]["metadata"] == {"k": "v"}

    def test_execute_job__reports_running_before_function__running_precedes_func_and_completed(
        self, mock_api, shutdown_event
    ):
        call_order = []

        def my_agent(**kwargs):
            call_order.append("func")
            return "ok"

        def track_report(job_id, *, status, **kwargs):
            call_order.append(status)

        mock_api.runners.report_job_result.side_effect = track_report

        registry.register("my_agent", my_agent, "proj", [], "")

        lp = in_process_loop.InProcessRunnerLoop(mock_api, "r-1", shutdown_event)
        job = LocalRunnerJob(id="j-1", agent_name="my_agent", inputs={})

        aio_loop = asyncio.new_event_loop()
        aio_loop.run_until_complete(lp._execute_job(job))
        aio_loop.close()

        assert call_order == ["running", "func", "completed"]

    def test_execute_job__running_report__uses_same_generated_trace_id_as_completed(
        self, mock_api, shutdown_event
    ):
        def my_agent(**kwargs):
            return "ok"

        registry.register("my_agent", my_agent, "proj", [], "")

        lp = in_process_loop.InProcessRunnerLoop(mock_api, "r-1", shutdown_event)
        job = LocalRunnerJob(id="j-1", agent_name="my_agent", inputs={})

        aio_loop = asyncio.new_event_loop()
        aio_loop.run_until_complete(lp._execute_job(job))
        aio_loop.close()

        calls = mock_api.runners.report_job_result.call_args_list
        assert len(calls) == 2
        running_kwargs = calls[0][1]
        completed_kwargs = calls[1][1]
        assert running_kwargs["status"] == "running"
        assert completed_kwargs["status"] == "completed"
        # Both calls share the same generated trace_id
        assert running_kwargs["trace_id"] == completed_kwargs["trace_id"]
        assert len(running_kwargs["trace_id"]) > 0

    def test_execute_job__typed_params__string_inputs_cast_before_call(
        self, mock_api, shutdown_event
    ):
        captured = {}

        def my_agent(query: str, count: int, score: float, active: bool, **kwargs):
            captured["query"] = query
            captured["count"] = count
            captured["score"] = score
            captured["active"] = active

        params = registry.extract_params(my_agent)
        registry.register("typed_agent", my_agent, "proj", params, "")

        lp = in_process_loop.InProcessRunnerLoop(mock_api, "r-1", shutdown_event)

        job = LocalRunnerJob(
            id="j-1",
            agent_name="typed_agent",
            inputs={"query": "hello", "count": "5", "score": "3.14", "active": "true"},
        )

        loop = asyncio.new_event_loop()
        loop.run_until_complete(lp._execute_job(job))
        loop.close()

        assert captured == {"query": "hello", "count": 5, "score": 3.14, "active": True}
        assert type(captured["count"]) is int
        assert type(captured["score"]) is float
        assert type(captured["active"]) is bool

    def test_execute_job__invalid_input_type__reports_failed(
        self, mock_api, shutdown_event
    ):
        def my_agent(count: int, **kwargs):
            pass

        params = registry.extract_params(my_agent)
        registry.register("typed_fail", my_agent, "proj", params, "")

        lp = in_process_loop.InProcessRunnerLoop(mock_api, "r-1", shutdown_event)
        job = LocalRunnerJob(
            id="j-1",
            agent_name="typed_fail",
            inputs={"count": "3.9"},
        )

        loop = asyncio.new_event_loop()
        loop.run_until_complete(lp._execute_job(job))
        loop.close()

        call_kwargs = mock_api.runners.report_job_result.call_args[1]
        assert call_kwargs["status"] == "failed"
        assert "TypeError" in call_kwargs["error"]

    def test_execute_job__report_failure__does_not_raise(
        self, mock_api, shutdown_event
    ):
        def my_agent(**kwargs):
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

        # All report calls go through _safe_report_job_result — failures are swallowed.
        assert mock_api.runners.report_job_result.call_count == 2


class TestInjectTraceId:
    def test_absent_key__injects_fresh_opik_args(self):
        inputs: dict = {}
        in_process_loop._inject_trace_id(inputs, "tid-1")
        assert inputs["opik_args"]["trace"]["id"] == "tid-1"

    def test_dict_value__merges_trace_id(self):
        inputs = {"opik_args": {"trace": {"tags": ["t"]}, "span": {"k": "v"}}}
        in_process_loop._inject_trace_id(inputs, "tid-2")
        assert inputs["opik_args"]["trace"]["id"] == "tid-2"
        assert inputs["opik_args"]["trace"]["tags"] == ["t"]
        assert inputs["opik_args"]["span"] == {"k": "v"}

    def test_explicit_none__leaves_inputs_unchanged(self):
        inputs: dict = {"opik_args": None}
        in_process_loop._inject_trace_id(inputs, "tid-3")
        assert inputs["opik_args"] is None

    def test_trace_none__treats_as_empty(self):
        inputs = {"opik_args": {"trace": None}}
        in_process_loop._inject_trace_id(inputs, "tid-4")
        assert inputs["opik_args"]["trace"]["id"] == "tid-4"

    def test_non_dict_opik_args__replaces_with_fresh(self):
        inputs: dict = {"opik_args": "unexpected"}
        in_process_loop._inject_trace_id(inputs, "tid-5")
        assert inputs["opik_args"]["trace"]["id"] == "tid-5"

    def test_does_not_mutate_original_dict(self):
        original_trace = {"tags": ["x"]}
        original_opik = {"trace": original_trace}
        inputs = {"opik_args": original_opik}
        in_process_loop._inject_trace_id(inputs, "tid-6")
        assert original_opik.get("trace", {}).get("id") is None
        assert original_trace.get("id") is None


class TestJobLogs:
    @staticmethod
    def _run_with_streamer(lp, coro):
        loop = asyncio.new_event_loop()
        lp._loop = loop
        from opik.runner.log_streamer import LogStreamer

        lp._log_streamer = LogStreamer(lp._api, loop)

        async def _wrapper():
            lp._log_streamer.start()
            await coro
            await asyncio.sleep(0.1)
            await lp._log_streamer.stop()

        loop.run_until_complete(_wrapper())
        loop.close()

    @staticmethod
    def _enqueue(lp, job_id, text):
        from opik.rest_api.types.local_runner_log_entry import LocalRunnerLogEntry

        lp._log_streamer._queue.put_nowait(
            (job_id, LocalRunnerLogEntry(stream="stdout", text=text))
        )

    def test_execute_job__logs_sent_when_present(self, mock_api, shutdown_event):
        lp = in_process_loop.InProcessRunnerLoop(mock_api, "r-1", shutdown_event)

        async def my_agent(**kwargs):
            self._enqueue(lp, "j-log-1", "hello\n")
            return "ok"

        registry.register("my_agent", my_agent, "proj", [], "")

        job = LocalRunnerJob(id="j-log-1", agent_name="my_agent", inputs={})
        self._run_with_streamer(lp, lp._execute_job(job))

        mock_api.runners.append_job_logs.assert_called_once()
        call_kwargs = mock_api.runners.append_job_logs.call_args[1]
        assert call_kwargs["job_id"] == "j-log-1"
        assert any("hello" in e.text for e in call_kwargs["request"])

    def test_execute_job__no_logs__append_not_called(self, mock_api, shutdown_event):
        async def silent_agent(**kwargs):
            return "ok"

        registry.register("silent_agent", silent_agent, "proj", [], "")

        lp = in_process_loop.InProcessRunnerLoop(mock_api, "r-1", shutdown_event)
        job = LocalRunnerJob(id="j-log-2", agent_name="silent_agent", inputs={})
        self._run_with_streamer(lp, lp._execute_job(job))

        mock_api.runners.append_job_logs.assert_not_called()

    def test_execute_job__exception__logs_sent_then_result_failed(
        self, mock_api, shutdown_event
    ):
        lp = in_process_loop.InProcessRunnerLoop(mock_api, "r-1", shutdown_event)

        async def failing_agent(**kwargs):
            self._enqueue(lp, "j-log-3", "about to fail\n")
            raise ValueError("boom")

        registry.register("failing_agent", failing_agent, "proj", [], "")

        job = LocalRunnerJob(id="j-log-3", agent_name="failing_agent", inputs={})
        self._run_with_streamer(lp, lp._execute_job(job))

        mock_api.runners.append_job_logs.assert_called_once()
        call_kwargs = mock_api.runners.report_job_result.call_args[1]
        assert call_kwargs["status"] == "failed"
