import dataclasses
import json
import logging
import os
import subprocess
import tempfile
import threading
import time
from typing import Any, Dict, List, Optional

from .. import id_helpers
from ..rest_api.client import OpikApi
from ..rest_api.types.local_runner_job import LocalRunnerJob
from ..rest_api.types.local_runner_log_entry import LocalRunnerLogEntry
from .agents_registry import AgentInfo
from .constants import LOG_BATCH_SIZE, LOG_FLUSH_INTERVAL_SECONDS

LOGGER = logging.getLogger(__name__)


@dataclasses.dataclass
class JobProcess:
    """Wraps a spawned agent subprocess so the runner loop can kill it on cancellation or shutdown."""

    job_id: str
    process: subprocess.Popen[bytes]

    def kill(self) -> None:
        try:
            self.process.kill()
        except OSError:
            pass


class JobExecutor:
    """Spawns agent subprocesses, streams their logs, and reports results.

    Each call to execute() resolves the agent, spawns a subprocess, blocks
    until it finishes (or times out), and reports the outcome to the API.
    Thread-safe: multiple pool threads can call execute() concurrently on
    the same instance since per-job state is stack-local and shared dicts
    are protected by a lock.
    """

    def __init__(
        self,
        api: OpikApi,
        active_jobs: Dict[str, JobProcess],
        cancelled_jobs: set,
        lock: threading.Lock,
    ) -> None:
        self._api = api
        self._active_jobs = active_jobs
        self._cancelled_jobs = cancelled_jobs
        self._lock = lock

    def execute(
        self,
        job: LocalRunnerJob,
        agents: Dict[str, AgentInfo],
    ) -> None:
        """Resolve the agent, spawn its subprocess, and block until completion."""
        job_id = job.id or ""
        agent_name = job.agent_name or ""
        inputs = job.inputs or {}

        agent = self._resolve_agent(job_id, agent_name, agents)
        if agent is None:
            return

        trace_id = id_helpers.generate_id()

        fd, result_file = tempfile.mkstemp(
            suffix=".json", prefix=f"opik_result_{job_id}_"
        )
        os.close(fd)

        proc = self._spawn_process(
            agent, job_id, inputs, trace_id, result_file, job.mask_id
        )
        if proc is None:
            return

        job_proc = JobProcess(job_id=job_id, process=proc)
        with self._lock:
            self._active_jobs[job_id] = job_proc

        try:
            proc.stdin.write(json.dumps(inputs).encode())  # type: ignore[union-attr]
            proc.stdin.close()  # type: ignore[union-attr]
        except OSError:
            pass

        self._wait_and_report(proc, job, agent, trace_id, result_file)

    def _resolve_agent(
        self,
        job_id: str,
        agent_name: str,
        agents: Dict[str, AgentInfo],
    ) -> Optional[AgentInfo]:
        agent = agents.get(agent_name)
        if agent is None:
            LOGGER.error("Unknown agent '%s' for job %s", agent_name, job_id)
            self._api.runners.report_job_result(
                job_id=job_id,
                status="failed",
                error=f"Unknown agent: {agent_name}",
            )
            return None

        if not os.path.isfile(agent.source_file):
            error_msg = (
                f"Agent '{agent_name}' source file not found: '{agent.source_file}'. "
                "Re-run the agent script to update registration."
            )
            LOGGER.error(error_msg)
            self._api.runners.report_job_result(
                job_id=job_id,
                status="failed",
                error=error_msg,
            )
            return None

        return agent

    def _spawn_process(
        self,
        agent: AgentInfo,
        job_id: str,
        inputs: Dict[str, Any],
        trace_id: str,
        result_file: str,
        mask_id: Optional[str],
    ) -> Optional[subprocess.Popen[bytes]]:
        """Launch the agent executable as a subprocess with piped I/O."""
        env = {
            **os.environ,
            "OPIK_AGENT": agent.name,
            "OPIK_RESULT_FILE": result_file,
            "OPIK_TRACE_ID": trace_id,
        }
        if mask_id:
            env["OPIK_MASK_ID"] = mask_id

        try:
            return subprocess.Popen(
                [agent.executable, agent.source_file],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=env,
            )
        except OSError as e:
            LOGGER.error("Failed to spawn process for job %s: %s", job_id, e)
            _cleanup_result_file(result_file)
            self._api.runners.report_job_result(
                job_id=job_id,
                status="failed",
                error=str(e),
            )
            return None

    def _wait_and_report(
        self,
        proc: subprocess.Popen[bytes],
        job: LocalRunnerJob,
        agent: AgentInfo,
        trace_id: str,
        result_file: str,
    ) -> None:
        """Block on proc.wait(), stream logs in parallel, then report the result."""
        job_id = job.id or ""

        stdout_thread = threading.Thread(
            target=_stream_logs,
            args=(proc.stdout, "stdout", self._api, job_id),
            daemon=True,
        )
        stderr_thread = threading.Thread(
            target=_stream_logs,
            args=(proc.stderr, "stderr", self._api, job_id),
            daemon=True,
        )
        stdout_thread.start()
        stderr_thread.start()

        timeout = job.timeout
        if timeout is None:
            timeout = agent.timeout
        try:
            proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()

        stdout_thread.join(timeout=5)
        stderr_thread.join(timeout=5)

        with self._lock:
            self._active_jobs.pop(job_id, None)
            was_cancelled = job_id in self._cancelled_jobs
            self._cancelled_jobs.discard(job_id)

        try:
            if was_cancelled:
                pass
            elif proc.returncode == 0:
                result = _read_result(result_file, job_id)
                self._api.runners.report_job_result(
                    job_id=job_id,
                    status="completed",
                    result=result,
                    trace_id=trace_id,
                )
            else:
                self._api.runners.report_job_result(
                    job_id=job_id,
                    status="failed",
                    error=f"Process exited with code {proc.returncode}",
                    trace_id=trace_id,
                )
        finally:
            _cleanup_result_file(result_file)


def _read_result(result_file: str, job_id: str) -> Optional[Dict[str, Any]]:
    try:
        with open(result_file, "r") as f:
            data = json.load(f)
        return data  # LocalRunnerJob.result expect a dict, not a bare value.
    except FileNotFoundError:
        LOGGER.warning("Result file missing for job %s: %s", job_id, result_file)
        return None
    except json.JSONDecodeError:
        LOGGER.warning(
            "Result file for job %s contains invalid JSON: %s", job_id, result_file
        )
        return None


def _cleanup_result_file(result_file: str) -> None:
    try:
        os.unlink(result_file)
    except OSError:
        pass


def _stream_logs(
    stream: Any,
    stream_name: str,
    api: OpikApi,
    job_id: str,
) -> None:
    buffer: List[Dict[str, Any]] = []
    last_flush = time.monotonic()

    for line in iter(stream.readline, b""):
        text = line.decode("utf-8", errors="replace").rstrip("\n")
        buffer.append(
            {
                "stream": stream_name,
                "text": text,
            }
        )

        now = time.monotonic()
        if (
            len(buffer) >= LOG_BATCH_SIZE
            or (now - last_flush) >= LOG_FLUSH_INTERVAL_SECONDS
        ):
            _flush_logs(buffer, api, job_id)
            buffer = []
            last_flush = now

    if buffer:
        _flush_logs(buffer, api, job_id)


def _flush_logs(
    logs: List[Dict[str, Any]],
    api: OpikApi,
    job_id: str,
) -> None:
    try:
        api.runners.append_job_logs(
            job_id,
            request=[
                LocalRunnerLogEntry(stream=e["stream"], text=e["text"]) for e in logs
            ],
        )
    except Exception:
        LOGGER.debug("Failed to flush logs for job %s", job_id, exc_info=True)
