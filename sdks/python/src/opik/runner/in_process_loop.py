"""In-process runner loop — heartbeat, poll, and execute jobs without subprocesses."""

import asyncio
import collections
import inspect
import logging
import random
import signal
import threading
import time
from typing import Callable, Optional

from ..api_objects.agent_config.context import agent_config_context
from ..rest_api.client import OpikApi
from ..rest_api.core.api_error import ApiError
from ..rest_api.types.local_runner_job import LocalRunnerJob
from . import registry

LOGGER = logging.getLogger(__name__)

POLL_IDLE_INTERVAL_SECONDS = 0.5
_CANCELLED_JOBS_TTL_SECONDS = 300
_CANCELLED_JOBS_MAX_SIZE = 10_000


class InProcessRunnerLoop:
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
        self._cancelled_jobs: collections.OrderedDict[str, float] = (
            collections.OrderedDict()
        )
        self._lock = threading.Lock()
        self._job_queue: asyncio.Queue[LocalRunnerJob] = asyncio.Queue()
        self._loop: Optional[asyncio.AbstractEventLoop] = None

    def run(self) -> None:
        self._install_signal_handlers()

        heartbeat_thread = threading.Thread(
            target=self._heartbeat_loop,
            daemon=True,
        )
        heartbeat_thread.start()

        poll_thread = threading.Thread(
            target=self._poll_loop,
            daemon=True,
        )
        poll_thread.start()

        self._run_job_loop()

    def _poll_loop(self) -> None:
        backoff = 1.0

        while not self._shutdown_event.is_set():
            try:
                job = self._api.runners.next_job(self._runner_id)
            except ApiError as e:
                if e.status_code == 204:
                    job = None
                else:
                    LOGGER.debug("Poll error (API %s)", e.status_code, exc_info=True)
                    self._backoff_wait(backoff)
                    backoff = min(backoff * 2, self._backoff_cap_seconds)
                    continue
            except Exception:
                LOGGER.debug("Error polling for jobs", exc_info=True)
                self._backoff_wait(backoff)
                backoff = min(backoff * 2, self._backoff_cap_seconds)
                continue

            if job is None:
                backoff = 1.0
                self._shutdown_event.wait(POLL_IDLE_INTERVAL_SECONDS)
                continue

            backoff = 1.0
            if self._loop is not None:
                self._loop.call_soon_threadsafe(self._job_queue.put_nowait, job)

    def _heartbeat_loop(self) -> None:
        while not self._shutdown_event.is_set():
            try:
                resp = self._api.runners.heartbeat(self._runner_id)

                cancelled_job_ids = resp.cancelled_job_ids or []
                now = time.monotonic()
                with self._lock:
                    for jid in cancelled_job_ids:
                        self._cancelled_jobs[jid] = now
                    self._prune_cancelled_jobs(now)

            except ApiError as e:
                if e.status_code == 410:
                    LOGGER.info("Runner deregistered (410), shutting down")
                    self._shutdown_event.set()
                    return
                LOGGER.debug("Heartbeat error", exc_info=True)
            except Exception:
                LOGGER.debug("Heartbeat error", exc_info=True)

            self._shutdown_event.wait(self._heartbeat_interval_seconds)

    def _run_job_loop(self) -> None:
        self._loop = asyncio.new_event_loop()
        try:
            self._loop.run_until_complete(self._job_consumer())
        finally:
            self._loop.close()

    async def _job_consumer(self) -> None:
        tasks: set[asyncio.Task] = set()
        while not self._shutdown_event.is_set():
            try:
                job = await asyncio.wait_for(
                    self._job_queue.get(),
                    timeout=0.5,
                )
            except asyncio.TimeoutError:
                continue

            task = asyncio.create_task(self._execute_job(job))
            tasks.add(task)
            task.add_done_callback(tasks.discard)

        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    async def _execute_job(self, job: LocalRunnerJob) -> None:
        job_id = job.id or ""
        agent_name = job.agent_name or ""
        inputs = dict(job.inputs) if job.inputs else {}

        with self._lock:
            if job_id in self._cancelled_jobs:
                del self._cancelled_jobs[job_id]
                return

        entrypoints = registry.get_all()
        entry = entrypoints.get(agent_name)
        if entry is None:
            LOGGER.error("Unknown agent '%s' for job %s", agent_name, job_id)
            try:
                self._api.runners.report_job_result(
                    job_id=job_id,
                    status="failed",
                    error=f"Unknown agent: {agent_name}",
                )
            except Exception:
                LOGGER.debug("Failed to report error for job %s", job_id, exc_info=True)
            return

        func: Callable = entry["func"]
        mask_id = job.mask_id

        if job.trace_id:
            opik_args = inputs.setdefault("opik_args", {})
            trace_args = opik_args.setdefault("trace", {})
            trace_args["id"] = job.trace_id

        trace_id = job.trace_id

        try:
            timeout = job.timeout
            if inspect.iscoroutinefunction(func):
                with agent_config_context(mask_id):
                    coro = func(**inputs)
                    if timeout:
                        result = await asyncio.wait_for(coro, timeout=timeout)
                    else:
                        result = await coro
            else:

                def _run_with_mask() -> object:
                    with agent_config_context(mask_id):
                        return func(**inputs)

                if timeout:
                    result = await asyncio.wait_for(
                        asyncio.get_running_loop().run_in_executor(
                            None, _run_with_mask
                        ),
                        timeout=timeout,
                    )
                else:
                    result = await asyncio.get_running_loop().run_in_executor(
                        None, _run_with_mask
                    )

            if not isinstance(result, (dict, str, int, float, bool, list, type(None))):
                result = str(result)

            try:
                self._api.runners.report_job_result(
                    job_id=job_id,
                    status="completed",
                    result={"result": result}
                    if not isinstance(result, dict)
                    else result,
                    trace_id=trace_id,
                )
            except Exception:
                LOGGER.debug(
                    "Failed to report result for job %s", job_id, exc_info=True
                )
        except asyncio.TimeoutError:
            LOGGER.warning("Job %s timed out", job_id)
            self._api.runners.report_job_result(
                job_id=job_id,
                status="failed",
                error="Job timed out",
                trace_id=trace_id,
            )
        except Exception as e:
            LOGGER.error("Job %s failed: %s", job_id, e, exc_info=True)
            try:
                self._api.runners.report_job_result(
                    job_id=job_id,
                    status="failed",
                    error=f"{type(e).__name__}: {e}",
                    trace_id=trace_id,
                )
            except Exception:
                LOGGER.debug("Failed to report error for job %s", job_id, exc_info=True)

    def _prune_cancelled_jobs(self, now: float) -> None:
        """Remove expired entries and enforce max size. Caller must hold self._lock."""
        cutoff = now - _CANCELLED_JOBS_TTL_SECONDS
        while self._cancelled_jobs:
            oldest_key, oldest_time = next(iter(self._cancelled_jobs.items()))
            if oldest_time > cutoff:
                break
            del self._cancelled_jobs[oldest_key]
        while len(self._cancelled_jobs) > _CANCELLED_JOBS_MAX_SIZE:
            self._cancelled_jobs.popitem(last=False)

    def _backoff_wait(self, backoff: float) -> None:
        wait = min(backoff, self._backoff_cap_seconds) * (0.5 + random.random() * 0.5)
        self._shutdown_event.wait(wait)

    def _install_signal_handlers(self) -> None:
        shutdown = self._shutdown_event

        def handler(signum: int, frame: object) -> None:
            LOGGER.info("Received signal %s, shutting down", signum)
            shutdown.set()

        try:
            signal.signal(signal.SIGTERM, handler)
            signal.signal(signal.SIGINT, handler)
        except ValueError:
            LOGGER.warning("Cannot install signal handlers outside main thread")
