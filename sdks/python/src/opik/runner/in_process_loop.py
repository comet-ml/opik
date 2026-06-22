"""In-process runner loop — heartbeat, poll, and execute jobs without subprocesses."""

import asyncio
import collections
import contextvars
import inspect
import json
import logging
import threading
import time
from typing import Any, Callable, Optional

from ..api_objects import type_helpers

from ..api_objects.agent_config.context import agent_config_context
from ..api_objects.prompt import mask_context as prompt_mask_context_module
from .. import id_helpers

from ..rest_api.client import OpikApi
from ..rest_api.core.api_error import ApiError
from ..rest_api.types.local_runner_job import LocalRunnerJob
from . import registry
from .bridge_handlers import common as bridge_common
from .context import reset_job_id, set_job_id
from .log_streamer import LogStreamer

LOGGER = logging.getLogger(__name__)

_POLL_IDLE_INTERVAL_SECONDS = 0.5
_CANCELLED_JOBS_TTL_SECONDS = 300
_CANCELLED_JOBS_MAX_SIZE = 10_000

# Number of consecutive poll failures before we surface the firewall/proxy hint.
# Below this, failures are transient blips the backoff is already retrying; from
# here on we warn on every attempt until a poll succeeds and resets the counter.
_POLL_FAILURE_HINT_THRESHOLD = 3


def cast_input_value(value: object, type_name: str) -> object:
    """Cast *value* to the native Python type indicated by *type_name*.

    *type_name* is a backend type name (``"integer"``, ``"boolean"``,
    ``"float"``, ``"string"``).  Unknown names are treated as ``"string"``.

    Raises :exc:`TypeError` for values that cannot be safely cast (e.g.
    ``"3.9"`` for ``"integer"``, or a ``bool`` for ``"integer"``).
    ``None`` is always returned unchanged.  ``dict``/``list`` values are
    JSON-serialised when the target type is ``"string"``.
    """
    if value is None:
        return value

    py_type: Any = type_helpers.backend_type_to_python_type(type_name)
    if py_type is None:
        # Unknown type: pass strings through, JSON-serialize complex types, str() otherwise
        if isinstance(value, str):
            return value
        if isinstance(value, (dict, list)):
            return json.dumps(value)
        return str(value)

    # backend_value_to_python_value uses str(value) for the str case, which gives
    # Python repr for dicts/lists instead of JSON — handle that separately first.
    if py_type is str and isinstance(value, (dict, list)):
        return json.dumps(value)

    return type_helpers.backend_value_to_python_value(value, py_type)


def _inject_trace_id(inputs: dict, trace_id: str) -> None:
    """Merge trace_id into inputs["opik_args"]["trace"]["id"].

    Skips injection when opik_args is explicitly set to None — callers that
    set the key to None signal they own that slot and we must not clobber it.
    """
    existing = inputs.get("opik_args")
    if existing is None and "opik_args" in inputs:
        return
    opik_args = dict(existing) if isinstance(existing, dict) else {}
    trace_args = dict(opik_args.get("trace") or {})
    trace_args["id"] = trace_id
    opik_args["trace"] = trace_args
    inputs["opik_args"] = opik_args


class InProcessRunnerLoop:
    def __init__(
        self,
        api: OpikApi,
        runner_id: str,
        shutdown_event: threading.Event,
        heartbeat_interval_seconds: float = 5.0,
        backoff_cap_seconds: float = 30.0,
        initial_backoff_seconds: float = 1.0,
        poll_idle_interval_seconds: float = _POLL_IDLE_INTERVAL_SECONDS,
    ) -> None:
        self._api = api
        self._runner_id = runner_id
        self._shutdown_event = shutdown_event
        self._heartbeat_interval_seconds = heartbeat_interval_seconds
        self._backoff_cap_seconds = backoff_cap_seconds
        self._initial_backoff_seconds = initial_backoff_seconds
        self._poll_idle_interval_seconds = poll_idle_interval_seconds
        self._cancelled_jobs: collections.OrderedDict[str, float] = (
            collections.OrderedDict()
        )
        self._lock = threading.Lock()
        self._job_queue: asyncio.Queue[LocalRunnerJob] = asyncio.Queue()
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._log_streamer: Optional[LogStreamer] = None

    def run(self) -> None:
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
        backoff = self._initial_backoff_seconds
        poll_failures = 0

        while not self._shutdown_event.is_set():
            try:
                job = self._api.runners.next_job(self._runner_id)
            except ApiError as e:
                poll_failures += 1
                self._log_poll_failure(poll_failures, e.status_code)
                bridge_common.backoff_wait(
                    self._shutdown_event, backoff, self._backoff_cap_seconds
                )
                backoff = min(backoff * 2, self._backoff_cap_seconds)
                continue
            except Exception:
                poll_failures += 1
                self._log_poll_failure(poll_failures, None)
                bridge_common.backoff_wait(
                    self._shutdown_event, backoff, self._backoff_cap_seconds
                )
                backoff = min(backoff * 2, self._backoff_cap_seconds)
                continue

            if poll_failures:
                LOGGER.info(
                    "Reconnected to Opik server after %d failed poll(s); resuming.",
                    poll_failures,
                )
                poll_failures = 0
            backoff = self._initial_backoff_seconds

            if job is None:
                self._shutdown_event.wait(self._poll_idle_interval_seconds)
                continue

            if self._loop is not None:
                self._loop.call_soon_threadsafe(self._job_queue.put_nowait, job)

    def _log_poll_failure(self, failures: int, status_code: Optional[int]) -> None:
        """Warn once poll failures are sustained, then on every further attempt.

        Failures below ``_POLL_FAILURE_HINT_THRESHOLD`` are transient blips the
        backoff is already retrying, so they stay on debug. From the threshold
        onward we warn on every attempt; the caller resets ``failures`` to 0 as
        soon as a poll succeeds.
        """
        detail = f"API {status_code}" if status_code is not None else "connection error"

        if failures < _POLL_FAILURE_HINT_THRESHOLD:
            LOGGER.debug(
                "Poll failed (%s), attempt %d", detail, failures, exc_info=True
            )
            return

        rate_limit_note = (
            " Opik is rate-limiting requests (HTTP 429)." if status_code == 429 else ""
        )
        LOGGER.warning(
            "Job polling has failed %d times in a row (%s).%s A firewall or proxy may "
            "be blocking the sustained connection to Opik. Check your network/proxy "
            "settings, or increase the poll interval with OPIK_RUNNER_POLL_INTERVAL.",
            failures,
            detail,
            rate_limit_note,
        )

    def _heartbeat_loop(self) -> None:
        while not self._shutdown_event.is_set():
            try:
                resp = self._api.runners.heartbeat(
                    self._runner_id, capabilities=["jobs", "bridge"]
                )

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
        self._log_streamer = LogStreamer(self._api, self._loop)
        self._log_streamer.install()
        try:
            self._loop.run_until_complete(self._job_consumer())
        finally:
            self._loop.run_until_complete(self._log_streamer.stop())
            self._loop.close()

    async def _job_consumer(self) -> None:
        assert self._log_streamer is not None
        self._log_streamer.start()
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
                LOGGER.debug("Skipping cancelled job %s", job_id)
                del self._cancelled_jobs[job_id]
                return

        entrypoints = registry.get_all()
        entry = entrypoints.get(agent_name)
        if entry is None:
            LOGGER.error("Unknown agent '%s' for job %s", agent_name, job_id)
            self._safe_report_job_result(
                job_id=job_id,
                status="failed",
                error=f"Unknown agent: {agent_name}",
            )
            return

        func: Callable = entry["func"]
        mask_id = job.mask_id
        masks = job.prompt_masks
        blueprint_name = job.blueprint_name

        trace_id = id_helpers.generate_id()

        self._safe_report_job_result(
            job_id=job_id,
            status="running",
            trace_id=trace_id,
        )

        token = set_job_id(job_id)
        ctx = contextvars.copy_context()
        try:
            params_by_name = {p.name: p for p in entry["params"]}
            for key in list(inputs.keys()):
                if key in params_by_name:
                    inputs[key] = cast_input_value(
                        inputs[key], params_by_name[key].type
                    )

            _inject_trace_id(inputs, trace_id)
            timeout = job.timeout
            if inspect.iscoroutinefunction(func):
                with (
                    agent_config_context(mask_id, blueprint_name),
                    prompt_mask_context_module.prompt_mask_context(masks),
                ):
                    coro = func(**inputs)
                    if timeout:
                        result = await asyncio.wait_for(coro, timeout=timeout)
                    else:
                        result = await coro
            else:

                def _run_sync() -> object:
                    with (
                        agent_config_context(mask_id, blueprint_name),
                        prompt_mask_context_module.prompt_mask_context(masks),
                    ):
                        return func(**inputs)

                if timeout:
                    result = await asyncio.wait_for(
                        asyncio.get_running_loop().run_in_executor(
                            None, ctx.run, _run_sync
                        ),
                        timeout=timeout,
                    )
                else:
                    result = await asyncio.get_running_loop().run_in_executor(
                        None, ctx.run, _run_sync
                    )

            if not isinstance(result, (dict, str, int, float, bool, list, type(None))):
                result = str(result)

            self._safe_report_job_result(
                job_id=job_id,
                status="completed",
                result={"result": result} if not isinstance(result, dict) else result,
                trace_id=trace_id,
            )
        except asyncio.TimeoutError:
            LOGGER.warning("Job %s timed out after %ss", job_id, timeout)
            self._safe_report_job_result(
                job_id=job_id,
                status="failed",
                error=f"Job timed out after {timeout}s",
                trace_id=trace_id,
            )
        except Exception as e:
            LOGGER.error("Job %s failed: %s", job_id, e, exc_info=True)
            self._safe_report_job_result(
                job_id=job_id,
                status="failed",
                error=f"{type(e).__name__}: {e}",
                trace_id=trace_id,
            )
        finally:
            reset_job_id(token)

    def _safe_report_job_result(self, job_id: str, **kwargs: object) -> None:
        """Report a job result, logging and swallowing any exception."""
        try:
            self._api.runners.report_job_result(job_id=job_id, **kwargs)
        except Exception:
            LOGGER.warning("Failed to report status for job %s", job_id, exc_info=True)

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
