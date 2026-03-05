"""Tests that the runner code works correctly with typed API models and public exports."""

import sys
import textwrap
import threading
from unittest.mock import MagicMock

from opik.rest_api.types.local_runner_heartbeat_response import (
    LocalRunnerHeartbeatResponse,
)
from opik.rest_api.types.local_runner_job import LocalRunnerJob
from opik.runner import job_executor, runner_loop
from opik.runner.agents_registry import AgentInfo


class TestJobExecutorWithTypedModels:
    """Ensure JobExecutor works with LocalRunnerJob pydantic models (not raw dicts)."""

    def test_execute__typed_job__reports_completed(self, tmp_path):
        script = tmp_path / "agent.py"
        script.write_text(
            textwrap.dedent("""
            import json, sys, os
            inputs = json.loads(sys.stdin.read())
            with open(os.environ["OPIK_RESULT_FILE"], "w") as f:
                json.dump({"result": f"echo: {inputs['msg']}"}, f)
        """)
        )

        api = MagicMock()
        executor = job_executor.JobExecutor(api, {}, set(), threading.Lock())

        agents = {
            "a": AgentInfo(name="a", executable=sys.executable, source_file=str(script))
        }
        job = LocalRunnerJob(
            id="j-typed", agent_name="a", inputs={"msg": "hi"}, runner_id="r-1"
        )

        executor.execute(job, agents)

        call_kwargs = api.runners.report_job_result.call_args[1]
        assert call_kwargs["status"] == "completed"
        assert call_kwargs["result"] == "echo: hi"

    def test_execute__typed_job_with_timeout__respects_timeout(self, tmp_path):
        script = tmp_path / "agent.py"
        script.write_text(
            textwrap.dedent("""
            import time, sys, json
            json.loads(sys.stdin.read())
            time.sleep(60)
        """)
        )

        api = MagicMock()
        executor = job_executor.JobExecutor(api, {}, set(), threading.Lock())

        agents = {
            "slow": AgentInfo(
                name="slow", executable=sys.executable, source_file=str(script)
            )
        }
        job = LocalRunnerJob(
            id="j-timeout",
            agent_name="slow",
            inputs={},
            runner_id="r-1",
            timeout=1,
        )

        executor.execute(job, agents)

        call_kwargs = api.runners.report_job_result.call_args[1]
        assert call_kwargs["status"] == "failed"

    def test_execute__typed_job_unknown_agent__reports_failed(self):
        api = MagicMock()
        executor = job_executor.JobExecutor(api, {}, set(), threading.Lock())

        job = LocalRunnerJob(
            id="j-unknown", agent_name="nope", inputs={}, runner_id="r-1"
        )

        executor.execute(job, {})

        call_kwargs = api.runners.report_job_result.call_args[1]
        assert call_kwargs["status"] == "failed"
        assert "Unknown agent" in call_kwargs["error"]


class TestHeartbeatWithTypedResponse:
    """Ensure runner_loop handles typed heartbeat responses (not raw dicts)."""

    def test_heartbeat__typed_response_with_cancellations__kills_jobs(self):
        api = MagicMock()
        shutdown = threading.Event()
        loop = runner_loop.RunnerLoop(
            api, "r-1", shutdown, heartbeat_interval_seconds=0.05
        )

        mock_proc = MagicMock()
        jp = job_executor.JobProcess(job_id="j-1", process=mock_proc)
        loop._active_jobs["j-1"] = jp

        call_count = 0

        def heartbeat_side_effect(runner_id):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return LocalRunnerHeartbeatResponse(cancelled_job_ids=["j-1"])
            shutdown.set()
            return LocalRunnerHeartbeatResponse(cancelled_job_ids=[])

        api.runners.heartbeat.side_effect = heartbeat_side_effect
        api.runners.register_agents = MagicMock(return_value=None)

        t = threading.Thread(target=loop._heartbeat_loop)
        t.start()
        t.join(timeout=5)

        mock_proc.kill.assert_called()

    def test_heartbeat__typed_response_empty__no_cancellations(self):
        api = MagicMock()
        shutdown = threading.Event()
        loop = runner_loop.RunnerLoop(
            api, "r-1", shutdown, heartbeat_interval_seconds=0.05
        )

        call_count = 0

        def heartbeat_side_effect(runner_id):
            nonlocal call_count
            call_count += 1
            if call_count >= 2:
                shutdown.set()
            return LocalRunnerHeartbeatResponse(cancelled_job_ids=[])

        api.runners.heartbeat.side_effect = heartbeat_side_effect
        api.runners.register_agents = MagicMock(return_value=None)

        t = threading.Thread(target=loop._heartbeat_loop)
        t.start()
        t.join(timeout=5)

        assert call_count >= 2


class TestEntrypointRegistration:
    """Ensure @entrypoint writes agent info to the registry file."""

    def test_entrypoint__decorated_function__registers_to_agents_file(self):
        from opik import entrypoint
        from opik.runner import agents_registry

        @entrypoint(name="test_agent", project="test-project")
        def my_agent(question: str) -> str:
            """A test agent."""
            return "answer"

        agents = agents_registry.load_agents()
        assert "test_agent" in agents
        agent = agents["test_agent"]
        assert agent.project == "test-project"
        assert agent.description == "A test agent."
        assert any(p.name == "question" for p in agent.params)


class TestTrackEntrypoint:
    """Ensure track(entrypoint=True) works end-to-end."""

    def test_track__entrypoint_true__decorates_without_error(self):
        from opik.decorator.tracker import track

        @track(entrypoint=True)
        def my_agent(question: str) -> str:
            return "answer"

        assert callable(my_agent)

    def test_track__entrypoint_false__decorates_without_error(self):
        from opik.decorator.tracker import track

        @track(entrypoint=False)
        def my_func(x: int) -> int:
            return x + 1

        assert callable(my_func)


class TestOpikEntrypoint:
    """Ensure entrypoint is usable from the public opik package."""

    def test_import__entrypoint__decorates_function(self):
        from opik import entrypoint

        @entrypoint
        def my_agent(question: str) -> str:
            return "answer"

        assert callable(my_agent)

    def test_import__entrypoint_with_args__decorates_function(self):
        from opik import entrypoint

        @entrypoint(name="custom", project="my-project")
        def my_agent(question: str) -> str:
            return "answer"

        assert callable(my_agent)
