import logging
import os
import random
import signal
import threading
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, Optional

from ..rest_api.client import OpikApi
from ..rest_api.core.api_error import ApiError
from ..rest_api.types.local_runner_job import LocalRunnerJob
from . import agents_registry, job_executor
from .agents_registry import AgentInfo
from .constants import (
    DEFAULT_BACKOFF_CAP_SECONDS,
    DEFAULT_BACKOFF_INITIAL_SECONDS,
    DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
    DEFAULT_MAX_WORKERS,
    POLL_IDLE_INTERVAL_SECONDS,
)

LOGGER = logging.getLogger(__name__)


class _Backoff:
    """Exponential backoff with jitter, resettable on success."""

    def __init__(self, cap: float) -> None:
        self._cap = cap
        self._delay = DEFAULT_BACKOFF_INITIAL_SECONDS

    def reset(self) -> None:
        self._delay = DEFAULT_BACKOFF_INITIAL_SECONDS

    def wait(self, event: threading.Event) -> None:
        jittered = min(self._delay, self._cap) * (0.5 + random.random() * 0.5)
        event.wait(jittered)
        self._delay = min(self._delay * 2, self._cap)


def _agents_to_payload(agents: Dict[str, AgentInfo]) -> Dict:
    return {
        name: {k: v for k, v in a.to_dict().items() if k != "name"}
        for name, a in agents.items()
    }


class RunnerLoop:
    """Main event loop that polls for jobs and dispatches them to a thread pool.

    Manages a bounded ThreadPoolExecutor for concurrent job execution,
    a heartbeat daemon for server communication and job cancellation,
    and graceful shutdown via signal handlers.
    """

    def __init__(
        self,
        api: OpikApi,
        runner_id: str,
        shutdown_event: threading.Event,
        heartbeat_interval_seconds: float = DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
        backoff_cap_seconds: float = DEFAULT_BACKOFF_CAP_SECONDS,
        max_workers: Optional[int] = None,
    ) -> None:
        self._api = api
        self._runner_id = runner_id
        self._shutdown_event = shutdown_event
        self._heartbeat_interval_seconds = heartbeat_interval_seconds
        self._backoff_cap_seconds = backoff_cap_seconds
        self._max_workers = max_workers or os.cpu_count() or DEFAULT_MAX_WORKERS
        self._active_jobs: Dict[str, job_executor.JobProcess] = {}
        self._cancelled_jobs: set = set()
        self._lock = threading.Lock()

    def run(self) -> None:
        """Poll for jobs and submit them to the thread pool until shutdown.

        A semaphore gates polling so we never fetch more jobs than the pool
        can handle. On shutdown, active subprocesses are killed first, then
        the pool is drained.
        """
        self._install_signal_handlers()

        heartbeat_thread = threading.Thread(
            target=self._heartbeat_loop,
            daemon=True,
        )
        heartbeat_thread.start()

        executor = job_executor.JobExecutor(
            self._api,
            self._active_jobs,
            self._cancelled_jobs,
            self._lock,
        )

        slot = threading.Semaphore(self._max_workers)
        pool = ThreadPoolExecutor(max_workers=self._max_workers)
        backoff = _Backoff(cap=self._backoff_cap_seconds)

        try:
            while not self._shutdown_event.is_set():
                if not slot.acquire(blocking=False):
                    self._shutdown_event.wait(POLL_IDLE_INTERVAL_SECONDS)
                    continue

                job = self._poll_job(backoff)
                if job is None:
                    slot.release()
                    continue

                submitted = False
                try:
                    agents = agents_registry.load_agents()
                    pool.submit(self._safe_execute, executor, job, agents, slot)
                    submitted = True
                finally:
                    if not submitted:
                        slot.release()
        except KeyboardInterrupt:
            self._shutdown_event.set()

        self._kill_active_jobs()
        pool.shutdown(wait=False, cancel_futures=True)
        LOGGER.info("Runner loop stopped")

    def _poll_job(self, backoff: _Backoff) -> Optional[LocalRunnerJob]:
        """Try to fetch the next job, handling errors with backoff.

        Returns the job on success, or None if no job is available or an
        error occurred. The caller must release the semaphore slot when
        None is returned.
        """
        try:
            job = self._api.runners.next_job(self._runner_id)
        except ApiError as e:
            if e.status_code == 204:
                job = None
            else:
                LOGGER.debug(
                    "Unexpected API error while polling for jobs", exc_info=True
                )
                backoff.wait(self._shutdown_event)
                return None
        except Exception:
            LOGGER.debug("Error polling for jobs", exc_info=True)
            backoff.wait(self._shutdown_event)
            return None

        if job is None:
            backoff.reset()
            self._shutdown_event.wait(POLL_IDLE_INTERVAL_SECONDS)
            return None

        backoff.reset()
        return job

    def _safe_execute(
        self,
        executor: job_executor.JobExecutor,
        job: LocalRunnerJob,
        agents: Dict[str, AgentInfo],
        slot: threading.Semaphore,
    ) -> None:
        """Run a job via the executor, catching crashes and releasing the pool slot."""
        try:
            executor.execute(job, agents)
        except Exception:
            LOGGER.error("Executor thread crashed for job %s", job.id, exc_info=True)
            try:
                self._api.runners.report_job_result(
                    job_id=job.id or "",
                    status="failed",
                    error="Internal runner error",
                )
            except Exception:
                LOGGER.debug("Failed to report crash for job %s", job.id, exc_info=True)
        finally:
            slot.release()

    def _heartbeat_loop(self) -> None:
        """Daemon loop that registers agents, sends heartbeats, and kills cancelled jobs."""
        while not self._shutdown_event.is_set():
            try:
                agents = agents_registry.load_agents()
                payload = _agents_to_payload(agents)
                self._api.runners.register_agents(self._runner_id, request=payload)

                resp = self._api.runners.heartbeat(self._runner_id)

                cancelled_job_ids = resp.cancelled_job_ids or []
                if cancelled_job_ids:
                    with self._lock:
                        for jid in cancelled_job_ids:
                            self._cancelled_jobs.add(jid)
                            jp = self._active_jobs.get(jid)
                            if jp:
                                jp.kill()
                                self._active_jobs.pop(jid, None)

            except ApiError as e:
                if e.status_code == 410:
                    LOGGER.info("Runner deregistered (410), shutting down")
                    self._shutdown_event.set()
                    return
                LOGGER.debug("Heartbeat error", exc_info=True)
            except Exception:
                LOGGER.debug("Heartbeat error", exc_info=True)

            self._shutdown_event.wait(self._heartbeat_interval_seconds)

    def _install_signal_handlers(self) -> None:
        shutdown = self._shutdown_event

        def handler(signum: int, frame: object) -> None:
            LOGGER.info("Received signal %s, shutting down", signum)
            shutdown.set()

        signal.signal(signal.SIGTERM, handler)
        signal.signal(signal.SIGINT, handler)

    def _kill_active_jobs(self) -> None:
        with self._lock:
            for jp in self._active_jobs.values():
                jp.kill()
            self._active_jobs.clear()
