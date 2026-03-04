import json
import subprocess
import sys
import textwrap
import threading
from unittest.mock import MagicMock

import pytest

from opik.runner import job_executor
from opik.runner.agents_registry import AgentInfo


@pytest.fixture
def mock_api():
    api = MagicMock()
    api.runners.append_job_logs = MagicMock()
    api.runners.report_job_result = MagicMock()
    return api


@pytest.fixture
def agents():
    return {
        "echo_agent": AgentInfo(
            name="echo_agent",
            executable=sys.executable,
            source_file="",
        )
    }


@pytest.fixture
def executor(mock_api):
    active_jobs: dict = {}
    cancelled_jobs: set = set()
    lock = threading.Lock()
    return job_executor.JobExecutor(mock_api, active_jobs, cancelled_jobs, lock)


@pytest.fixture
def cancelled_executor(mock_api):
    active_jobs: dict = {}
    cancelled_jobs: set = set()
    lock = threading.Lock()
    return job_executor.JobExecutor(
        mock_api, active_jobs, cancelled_jobs, lock
    ), cancelled_jobs


def _write_agent_script(tmp_path, code):
    script = tmp_path / "agent.py"
    script.write_text(textwrap.dedent(code))
    return str(script)


class TestExecuteSuccess:
    def test_execute__successful_agent__reports_completed(
        self, mock_api, executor, tmp_path
    ):
        script = _write_agent_script(
            tmp_path,
            """
            import json, sys, os
            inputs = json.loads(sys.stdin.read())
            result_file = os.environ["OPIK_RESULT_FILE"]
            with open(result_file, "w") as f:
                json.dump({"result": f"echo: {inputs['msg']}"}, f)
        """,
        )

        agents = {
            "echo_agent": AgentInfo(
                name="echo_agent",
                executable=sys.executable,
                source_file=script,
            )
        }

        job = {
            "id": "j-1",
            "agent_name": "echo_agent",
            "inputs": {"msg": "hello"},
            "runner_id": "r-1",
        }
        executor.execute(job, agents)

        mock_api.runners.report_job_result.assert_called_once()
        call_kwargs = mock_api.runners.report_job_result.call_args[1]
        assert call_kwargs["status"] == "completed"
        assert call_kwargs["result"] == "echo: hello"

    def test_execute__successful_agent__includes_trace_id(
        self, mock_api, executor, tmp_path
    ):
        script = _write_agent_script(
            tmp_path,
            """
            import json, sys, os
            inputs = json.loads(sys.stdin.read())
            with open(os.environ["OPIK_RESULT_FILE"], "w") as f:
                json.dump({"result": "ok"}, f)
        """,
        )

        agents = {
            "a": AgentInfo(name="a", executable=sys.executable, source_file=script)
        }
        job = {"id": "j-trace", "agent_name": "a", "inputs": {}, "runner_id": "r-1"}

        executor.execute(job, agents)

        call_kwargs = mock_api.runners.report_job_result.call_args[1]
        assert call_kwargs["trace_id"] is not None
        assert len(call_kwargs["trace_id"]) > 0

    def test_execute__completed_job__removed_from_active_jobs(
        self, mock_api, executor, tmp_path
    ):
        script = _write_agent_script(
            tmp_path,
            """
            import json, sys, os
            inputs = json.loads(sys.stdin.read())
            with open(os.environ["OPIK_RESULT_FILE"], "w") as f:
                json.dump({"result": "ok"}, f)
        """,
        )

        agents = {
            "a": AgentInfo(name="a", executable=sys.executable, source_file=script)
        }
        job = {"id": "j-2", "agent_name": "a", "inputs": {}, "runner_id": "r-1"}

        executor.execute(job, agents)
        assert "j-2" not in executor._active_jobs


class TestExecuteFailure:
    def test_execute__nonzero_exit__reports_failed(self, mock_api, executor, tmp_path):
        script = _write_agent_script(
            tmp_path,
            """
            import sys
            sys.exit(1)
        """,
        )

        agents = {
            "bad": AgentInfo(name="bad", executable=sys.executable, source_file=script)
        }
        job = {"id": "j-3", "agent_name": "bad", "inputs": {}, "runner_id": "r-1"}

        executor.execute(job, agents)

        call_kwargs = mock_api.runners.report_job_result.call_args[1]
        assert call_kwargs["status"] == "failed"
        assert "exit" in call_kwargs["error"].lower()

    def test_execute__unknown_agent__reports_failed(self, mock_api, executor):
        job = {"id": "j-4", "agent_name": "unknown", "inputs": {}, "runner_id": "r-1"}
        executor.execute(job, {})

        call_kwargs = mock_api.runners.report_job_result.call_args[1]
        assert call_kwargs["status"] == "failed"
        assert "Unknown agent" in call_kwargs["error"]

    def test_execute__missing_source_file__reports_failed(self, mock_api, executor):
        agents = {
            "missing": AgentInfo(
                name="missing",
                executable=sys.executable,
                source_file="/nonexistent/agent.py",
            )
        }
        job = {
            "id": "j-missing",
            "agent_name": "missing",
            "inputs": {},
            "runner_id": "r-1",
        }

        executor.execute(job, agents)

        call_kwargs = mock_api.runners.report_job_result.call_args[1]
        assert call_kwargs["status"] == "failed"
        assert "source file not found" in call_kwargs["error"]
        assert "Re-run the agent script" in call_kwargs["error"]

    def test_execute__spawn_error__reports_failed(self, mock_api, executor):
        agents = {
            "bad": AgentInfo(
                name="bad",
                executable="/nonexistent/python",
                source_file="/x.py",
            )
        }
        job = {"id": "j-5", "agent_name": "bad", "inputs": {}, "runner_id": "r-1"}

        executor.execute(job, agents)

        call_kwargs = mock_api.runners.report_job_result.call_args[1]
        assert call_kwargs["status"] == "failed"

    def test_execute__no_job_timeout__uses_agent_timeout(
        self, mock_api, executor, tmp_path
    ):
        script = _write_agent_script(
            tmp_path,
            """
            import time, sys, json
            json.loads(sys.stdin.read())
            time.sleep(60)
        """,
        )

        agents = {
            "slow": AgentInfo(
                name="slow",
                executable=sys.executable,
                source_file=script,
                timeout=1,
            )
        }
        job = {
            "id": "j-timeout-fallback",
            "agent_name": "slow",
            "inputs": {},
            "runner_id": "r-1",
        }

        executor.execute(job, agents)

        call_kwargs = mock_api.runners.report_job_result.call_args[1]
        assert call_kwargs["status"] == "failed"

    def test_execute__cancelled_job__skips_report(
        self, mock_api, cancelled_executor, tmp_path
    ):
        executor, cancelled_jobs = cancelled_executor
        script = _write_agent_script(
            tmp_path,
            """
            import sys, json
            json.loads(sys.stdin.read())
            sys.exit(-9)
        """,
        )

        agents = {
            "a": AgentInfo(name="a", executable=sys.executable, source_file=script)
        }
        job = {"id": "j-cancel", "agent_name": "a", "inputs": {}, "runner_id": "r-1"}
        cancelled_jobs.add("j-cancel")

        executor.execute(job, agents)

        mock_api.runners.report_job_result.assert_not_called()

    def test_execute__job_timeout__kills_and_reports_failed(
        self, mock_api, executor, tmp_path
    ):
        script = _write_agent_script(
            tmp_path,
            """
            import time, sys, json
            json.loads(sys.stdin.read())
            time.sleep(60)
        """,
        )

        agents = {
            "slow": AgentInfo(
                name="slow",
                executable=sys.executable,
                source_file=script,
            )
        }
        job = {
            "id": "j-6",
            "agent_name": "slow",
            "inputs": {},
            "runner_id": "r-1",
            "timeout": 1,
        }

        executor.execute(job, agents)

        call_kwargs = mock_api.runners.report_job_result.call_args[1]
        assert call_kwargs["status"] == "failed"


class TestLogStreaming:
    def test_execute__stdout_output__streams_as_stdout_logs(
        self, mock_api, executor, tmp_path
    ):
        script = _write_agent_script(
            tmp_path,
            """
            import json, sys, os
            json.loads(sys.stdin.read())
            print("hello stdout")
            with open(os.environ["OPIK_RESULT_FILE"], "w") as f:
                json.dump({"result": "ok"}, f)
        """,
        )

        agents = {
            "a": AgentInfo(name="a", executable=sys.executable, source_file=script)
        }
        job = {"id": "j-7", "agent_name": "a", "inputs": {}, "runner_id": "r-1"}

        executor.execute(job, agents)

        assert mock_api.runners.append_job_logs.called
        all_logs = []
        for c in mock_api.runners.append_job_logs.call_args_list:
            for entry in c[1]["request"]:
                all_logs.append({"stream": entry.stream, "text": entry.text})
        stdout_logs = [entry for entry in all_logs if entry["stream"] == "stdout"]
        assert any("hello stdout" in entry["text"] for entry in stdout_logs)

    def test_execute__stderr_output__streams_as_stderr_logs(
        self, mock_api, executor, tmp_path
    ):
        script = _write_agent_script(
            tmp_path,
            """
            import json, sys, os
            json.loads(sys.stdin.read())
            print("error msg", file=sys.stderr)
            with open(os.environ["OPIK_RESULT_FILE"], "w") as f:
                json.dump({"result": "ok"}, f)
        """,
        )

        agents = {
            "a": AgentInfo(name="a", executable=sys.executable, source_file=script)
        }
        job = {"id": "j-8", "agent_name": "a", "inputs": {}, "runner_id": "r-1"}

        executor.execute(job, agents)

        assert mock_api.runners.append_job_logs.called
        all_logs = []
        for c in mock_api.runners.append_job_logs.call_args_list:
            for entry in c[1]["request"]:
                all_logs.append({"stream": entry.stream, "text": entry.text})
        stderr_logs = [entry for entry in all_logs if entry["stream"] == "stderr"]
        assert any("error msg" in entry["text"] for entry in stderr_logs)


class TestJobProcess:
    def test_kill__running_process__terminates(self, tmp_path):
        script = _write_agent_script(
            tmp_path,
            """
            import time
            time.sleep(60)
        """,
        )

        proc = subprocess.Popen([sys.executable, script])
        jp = job_executor.JobProcess(job_id="j-x", process=proc)
        jp.kill()
        proc.wait(timeout=5)
        assert proc.returncode is not None

    def test_kill__already_finished__noop(self, tmp_path):
        script = _write_agent_script(tmp_path, "pass")
        proc = subprocess.Popen([sys.executable, script])
        proc.wait()
        jp = job_executor.JobProcess(job_id="j-y", process=proc)
        jp.kill()


class TestReadResult:
    def test_read_result__valid_file__returns_result(self, tmp_path):
        rf = str(tmp_path / "result.json")
        with open(rf, "w") as f:
            json.dump({"result": "hello"}, f)
        assert job_executor._read_result(rf) == "hello"

    def test_read_result__missing_file__returns_none(self, tmp_path):
        assert job_executor._read_result(str(tmp_path / "nope.json")) is None

    def test_read_result__corrupted_json__returns_none(self, tmp_path):
        rf = str(tmp_path / "bad.json")
        with open(rf, "w") as f:
            f.write("{bad")
        assert job_executor._read_result(rf) is None
