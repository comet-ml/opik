import logging
import random
import signal
import threading
from typing import Dict

from ..rest_api.client import OpikApi
from ..rest_api.core.api_error import ApiError
from . import agents_registry, job_executor
from .agents_registry import AgentInfo

LOGGER = logging.getLogger(__name__)

POLL_IDLE_INTERVAL_SECONDS = 0.5


def _agents_to_payload(agents: Dict[str, AgentInfo]) -> Dict:
    return {
        name: {k: v for k, v in a.to_dict().items() if k != "name"}
        for name, a in agents.items()
    }


class RunnerLoop:
    def __init__(
        self,
        api: OpikApi,
        runner_id: str,
        shutdown_event: threading.Event,
        heartbeat_interval_seconds: float = 5.0,
        backoff_cap_seconds: float = 30.0,
    ) -> None:
        self._api = api
        self._runner_id = runner_id
        self._shutdown_event = shutdown_event
        self._heartbeat_interval_seconds = heartbeat_interval_seconds
        self._backoff_cap_seconds = backoff_cap_seconds
        self._active_jobs: Dict[str, job_executor.JobProcess] = {}
        self._cancelled_jobs: set = set()
        self._lock = threading.Lock()

    def run(self) -> None:
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

        backoff = 1.0

        try:
            while not self._shutdown_event.is_set():
                try:
                    job = self._api.runners.next_job(self._runner_id)
                except ApiError as e:
                    if e.status_code == 204:
                        job = None
                    else:
                        raise
                except Exception:
                    LOGGER.debug("Error polling for jobs", exc_info=True)
                    wait = min(backoff, self._backoff_cap_seconds) * (
                        0.5 + random.random() * 0.5
                    )
                    self._shutdown_event.wait(wait)
                    backoff = min(backoff * 2, self._backoff_cap_seconds)
                    continue

                if job is None:
                    backoff = 1.0
                    self._shutdown_event.wait(POLL_IDLE_INTERVAL_SECONDS)
                    continue

                backoff = 1.0
                agents = agents_registry.load_agents()

                thread = threading.Thread(
                    target=self._safe_execute,
                    args=(executor, job, agents),
                    daemon=True,
                )
                thread.start()
        except KeyboardInterrupt:
            self._shutdown_event.set()

        self._kill_active_jobs()
        LOGGER.info("Runner loop stopped")

    def _safe_execute(
        self,
        executor: job_executor.JobExecutor,
        job: Dict,
        agents: Dict,
    ) -> None:
        try:
            executor.execute(job, agents)
        except Exception:
            LOGGER.error(
                "Executor thread crashed for job %s", job.get("id"), exc_info=True
            )
            try:
                self._api.runners.report_job_result(
                    job_id=job.get("id", ""),
                    status="failed",
                    error="Internal runner error",
                )
            except Exception:
                LOGGER.debug(
                    "Failed to report crash for job %s", job.get("id"), exc_info=True
                )

    def _heartbeat_loop(self) -> None:
        while not self._shutdown_event.is_set():
            try:
                agents = agents_registry.load_agents()
                payload = _agents_to_payload(agents)
                self._api.runners.register_agents(self._runner_id, request=payload)

                resp = self._api.runners.heartbeat(self._runner_id)

                cancelled_job_ids = resp.get("cancelled_job_ids", [])
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
