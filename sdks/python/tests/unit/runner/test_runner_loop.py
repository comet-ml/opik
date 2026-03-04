import threading
import time
from unittest.mock import MagicMock, patch

import pytest

from opik.rest_api.core.api_error import ApiError
from opik.runner import runner_loop, job_executor, agents_registry


@pytest.fixture
def mock_api():
    api = MagicMock()
    api.runners.register_agents = MagicMock(return_value=None)
    api.runners.heartbeat = MagicMock(return_value={"cancelled_job_ids": []})
    api.runners.next_job = MagicMock(return_value=None)
    api.runners.report_job_result = MagicMock()
    api.runners.append_job_logs = MagicMock()
    return api


@pytest.fixture
def shutdown_event():
    return threading.Event()


@pytest.fixture
def loop(mock_api, shutdown_event):
    return runner_loop.RunnerLoop(
        mock_api,
        "r-1",
        shutdown_event,
        heartbeat_interval_seconds=100,
    )


class TestMainLoop:
    def test_run__no_jobs__polls_repeatedly(self, mock_api, shutdown_event, loop):
        call_count = 0

        def side_effect(runner_id):
            nonlocal call_count
            call_count += 1
            if call_count >= 3:
                shutdown_event.set()
            return None

        mock_api.runners.next_job.side_effect = side_effect

        loop.run()
        assert call_count >= 3

    def test_run__204__returns_none(self, mock_api, shutdown_event, loop):
        call_count = 0

        def side_effect(runner_id):
            nonlocal call_count
            call_count += 1
            if call_count >= 3:
                shutdown_event.set()
            raise ApiError(status_code=204)

        mock_api.runners.next_job.side_effect = side_effect

        loop.run()
        assert call_count >= 3

    def test_run__job_available__dispatches_to_executor(
        self, mock_api, shutdown_event, loop
    ):
        job = {"id": "j-1", "agent_name": "test", "inputs": {"q": "hi"}}
        call_count = 0

        def next_job_side_effect(runner_id):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return job
            shutdown_event.set()
            return None

        mock_api.runners.next_job.side_effect = next_job_side_effect

        with patch.object(job_executor.JobExecutor, "execute") as mock_execute:
            loop.run()
            time.sleep(0.1)

            assert mock_execute.called
            executed_job = mock_execute.call_args[0][0]
            assert executed_job["id"] == "j-1"

    def test_run__poll_error__backs_off(self, mock_api, shutdown_event):
        loop = runner_loop.RunnerLoop(
            mock_api,
            "r-1",
            shutdown_event,
            heartbeat_interval_seconds=100,
            backoff_cap_seconds=0.01,
        )
        call_count = 0

        def side_effect(runner_id):
            nonlocal call_count
            call_count += 1
            if call_count >= 2:
                shutdown_event.set()
                return None
            raise ConnectionError("fail")

        mock_api.runners.next_job.side_effect = side_effect

        loop.run()
        assert call_count >= 2

    def test_run__shutdown_set__exits_immediately(self, mock_api, shutdown_event, loop):
        shutdown_event.set()
        loop.run()
        mock_api.runners.next_job.assert_not_called()


class TestSafeExecute:
    def test_safe_execute__crash__reports_failed(self, mock_api, shutdown_event, loop):
        job = {"id": "j-crash", "agent_name": "a", "inputs": {}, "runner_id": "r-1"}
        executor = job_executor.JobExecutor(
            mock_api,
            loop._active_jobs,
            loop._cancelled_jobs,
            loop._lock,
        )

        with patch.object(executor, "execute", side_effect=RuntimeError("boom")):
            loop._safe_execute(executor, job, {})

        mock_api.runners.report_job_result.assert_called_once()
        call_kwargs = mock_api.runners.report_job_result.call_args[1]
        assert call_kwargs["status"] == "failed"
        assert call_kwargs["error"] == "Internal runner error"


class TestHeartbeatLoop:
    def test_heartbeat__normal__registers_and_heartbeats(
        self, mock_api, shutdown_event, loop
    ):
        loop._heartbeat_interval_seconds = 0.05

        agents_registry.register_agent(
            agents_registry.AgentInfo(
                name="a",
                executable="/bin/python",
                source_file="/a.py",
            )
        )

        call_count = 0

        def heartbeat_side_effect(runner_id):
            nonlocal call_count
            call_count += 1
            if call_count >= 2:
                shutdown_event.set()
            return {"cancelled_job_ids": []}

        mock_api.runners.heartbeat.side_effect = heartbeat_side_effect

        t = threading.Thread(target=loop._heartbeat_loop)
        t.start()
        t.join(timeout=5)

        assert mock_api.runners.register_agents.called
        assert mock_api.runners.heartbeat.called

    def test_heartbeat__cancelled_ids__kills_jobs(self, mock_api, shutdown_event, loop):
        loop._heartbeat_interval_seconds = 0.05

        mock_proc = MagicMock()
        jp = job_executor.JobProcess(job_id="j-1", process=mock_proc)
        loop._active_jobs["j-1"] = jp

        call_count = 0

        def heartbeat_side_effect(runner_id):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return {"cancelled_job_ids": ["j-1"]}
            shutdown_event.set()
            return {"cancelled_job_ids": []}

        mock_api.runners.heartbeat.side_effect = heartbeat_side_effect

        t = threading.Thread(target=loop._heartbeat_loop)
        t.start()
        t.join(timeout=5)

        mock_proc.kill.assert_called()
        assert "j-1" in loop._cancelled_jobs

    def test_heartbeat__410__triggers_shutdown(self, mock_api, shutdown_event, loop):
        loop._heartbeat_interval_seconds = 0.05
        mock_api.runners.heartbeat.side_effect = ApiError(status_code=410)

        t = threading.Thread(target=loop._heartbeat_loop)
        t.start()
        t.join(timeout=5)

        assert shutdown_event.is_set()


class TestSignalHandlers:
    def test_signal_handlers__installed__sets_handlers(self, shutdown_event, loop):
        loop._install_signal_handlers()


class TestKillActiveJobs:
    def test_kill_active__has_jobs__kills_and_clears(self, mock_api, shutdown_event):
        loop = runner_loop.RunnerLoop(mock_api, "r-1", shutdown_event)
        mock_proc = MagicMock()
        jp = job_executor.JobProcess(job_id="j-1", process=mock_proc)
        loop._active_jobs["j-1"] = jp

        loop._kill_active_jobs()

        mock_proc.kill.assert_called()
        assert len(loop._active_jobs) == 0
