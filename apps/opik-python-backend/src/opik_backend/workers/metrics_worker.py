import logging
import re
from typing import Mapping

from rq import Worker
from rq.job import Job
from rq.queue import Queue
from opentelemetry import metrics
from opentelemetry.metrics import get_meter

from .death_penalty import NoOpDeathPenalty
from opik_backend.utils.env_utils import get_env_int

logger = logging.getLogger(__name__)

# Failure TTL: how long failed jobs are kept in Redis (default: 1 day = 86400 seconds)
# Configurable via RQ_WORKER_TTL_FAILURE environment variable
DEFAULT_FAILURE_TTL = 86400
RQ_FAILURE_TTL = get_env_int("RQ_WORKER_TTL_FAILURE", DEFAULT_FAILURE_TTL)

meter = get_meter(__name__)

jobs_processed_counter = meter.create_counter(
    name="rq_worker.jobs.processed",
    description="Total number of jobs processed",
    unit="1",
)

jobs_failed_counter = meter.create_counter(
    name="rq_worker.jobs.failed",
    description="Total number of jobs that failed",
    unit="1",
)

jobs_succeeded_counter = meter.create_counter(
    name="rq_worker.jobs.succeeded",
    description="Total number of jobs that succeeded",
    unit="1",
)

processing_time_histogram = meter.create_histogram(
    name="rq_worker.job.processing_time",
    description="Time spent processing a job (from start to finish)",
    unit="ms",
)

queue_wait_time_histogram = meter.create_histogram(
    name="rq_worker.job.queue_wait_time",
    description="Time job spent waiting in queue (from creation to processing)",
    unit="ms",
)

total_job_time_histogram = meter.create_histogram(
    name="rq_worker.job.total_time",
    description="Total job time (from creation to completion)",
    unit="ms",
)

# Track concurrent jobs using UpDownCounter (gauge-like behavior)
# UpDownCounter is thread-safe internally - no external locking needed
concurrent_jobs_counter = meter.create_up_down_counter(
    name="rq_worker.jobs.concurrent",
    description="Number of jobs currently being processed",
    unit="1",
)


class MetricsWorker(Worker):
    """
    Custom RQ Worker that emits OpenTelemetry metrics.

    Architecture: single MeterProvider/Exporter per pod.

    The pod runs gunicorn (--workers 1 --threads N) plus N RQ worker threads
    in that same process. The parent's OTel SDK is initialized once and is the
    only metric exporter for the pod. RQ's default Worker.execute_job forks a
    child per job; without intervention each forked child would carry an
    inherited MeterProvider + PeriodicExportingMetricReader and emit its own
    `system_cpu_time`/`system_memory_*` series. Because the Python SDK's
    default Resource has no per-process identifier, every child's series
    collides with the parent and siblings on the same labels, and Prometheus
    rejects the remote-write batch with HTTP 400 "duplicate sample for
    timestamp".

    Two coordinated changes keep the pod to a single metric exporter:

    1. `main_work_horse` (runs post-fork in the child) shuts down the
       inherited MeterProvider so the child contributes no metrics. The
       TracerProvider is intentionally left alone -- span IDs are unique per
       span, so traces produced by auto-instrumentation inside a job (Flask,
       Requests, ...) remain useful and don't suffer the same dedup issue.

    2. `execute_job` (runs in the parent) wraps the call to RQ's
       `super().execute_job()` and records the per-job counters/histograms
       there, reading the final timestamps and status from Redis via
       `job.refresh()` after the child exits.

    Configuration:
        RQ_FAILURE_TTL: TTL for failed jobs in seconds (default: 86400 = 1 day)
    """

    death_penalty_class = NoOpDeathPenalty

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._failure_ttl = RQ_FAILURE_TTL
        logger.info(f"MetricsWorker initialized with failure_ttl={self._failure_ttl}s")

    def handle_job_failure(self, job: Job, queue: Queue, started_job_registry=None, exc_string=''):
        """Handle job failure with custom failure TTL.

        Override the default failure_ttl (1 year) with a configurable value.
        Signature matches RQ 2.6.0: handle_job_failure(job, queue, started_job_registry, exc_string)
        """
        if job.failure_ttl is None:
            job.failure_ttl = self._failure_ttl
            logger.debug(f"Set failure_ttl={self._failure_ttl}s for job {job.id}")

        return super().handle_job_failure(job, queue, started_job_registry, exc_string)

    def main_work_horse(self, job: Job, queue: Queue):
        """RQ post-fork entry point — runs in the forked child process.

        Silence the MeterProvider the child inherited from the parent so the
        pod ends up with a single metric exporter. See the class docstring for
        the full rationale.
        """
        try:
            metrics.get_meter_provider().shutdown()
        except Exception:
            logger.debug(
                "Failed to shut down MeterProvider in forked child; "
                "duplicate-sample errors may resume",
                exc_info=True,
            )
        return super().main_work_horse(job, queue)

    def execute_job(self, job: Job, queue: Queue) -> bool:
        """Execute a job and record OTel metrics from the parent process.

        Per-job metric emission lives here (parent) rather than in
        `perform_job` (child) so the pod has exactly one MeterProvider+Reader
        chain. Sequence:

          1. Bump `concurrent_jobs_counter`.
          2. Call `super().execute_job` which forks and waits for the child.
          3. Refresh the job from Redis to read the final timestamps and
             status the child wrote.
          4. Record `jobs_processed`, `jobs_succeeded`/`jobs_failed`,
             `processing_time`, `total_time`, and `queue_wait_time` (from
             `job.started_at - job.created_at` after refresh, preserving the
             pre-refactor SLI definition).
        """
        func_name = getattr(job, "func_name", None) or "unknown"
        queue_name = queue.name
        metric_attributes = {"queue": queue_name, "function": func_name}

        result: bool = False
        execute_exc: "BaseException | None" = None

        # Pair concurrent_jobs_counter +1/-1 via a try/finally that brackets
        # everything below — if the inner block raises (including a metric
        # call), the matching decrement still runs.
        concurrent_jobs_counter.add(1, metric_attributes)
        try:
            try:
                logger.debug(f"execute_job called for job {job.id}, status: {job.get_status()}")
                result = super().execute_job(job, queue)
                logger.debug(f"execute_job completed for job {job.id}")
            except Exception as e:
                execute_exc = e
                logger.error(
                    f"execute_job FAILED for job {job.id}: {type(e).__name__}: {e}",
                    exc_info=True,
                )
                raise
            finally:
                self._record_job_completion_metrics(job, metric_attributes, execute_exc)
        finally:
            concurrent_jobs_counter.add(-1, metric_attributes)

        return result

    @staticmethod
    def _record_job_completion_metrics(
        job: Job,
        metric_attributes: "Mapping[str, str]",
        execute_exc: "BaseException | None",
    ) -> None:
        """Read the final job state from Redis and emit the per-job metrics.

        Runs from the parent process after the forked child has exited. The
        child writes `started_at`, `ended_at`, status, and `exc_info` to Redis
        before exiting; `job.refresh()` here pulls those values into the local
        Job object so we can record durations, queue wait, and success/failure
        counters from the parent.
        """
        try:
            job.refresh()
            refresh_ok = True
        except Exception:
            logger.debug(
                "job.refresh() failed for job %s — terminal state unavailable",
                getattr(job, "id", "unknown"),
                exc_info=True,
            )
            refresh_ok = False

        jobs_processed_counter.add(1, metric_attributes)

        # Decide success vs failure. `job.is_failed` in RQ triggers another
        # Redis round-trip (`get_status()`), so we only consult it when the
        # earlier refresh succeeded; otherwise we'd risk raising from this
        # finally-block and surfacing a completed job as a worker error.
        if execute_exc is not None:
            # Parent-side exception is locally reliable; doesn't need Redis.
            jobs_failed_counter.add(
                1, {**metric_attributes, "error_type": type(execute_exc).__name__}
            )
        elif not refresh_ok:
            # We can't tell whether the child succeeded or failed without
            # Redis. Emit an explicit outcome with `error_type="RefreshFailed"`
            # rather than silently dropping the terminal metric.
            jobs_failed_counter.add(
                1, {**metric_attributes, "error_type": "RefreshFailed"}
            )
        elif getattr(job, "is_failed", False):
            jobs_failed_counter.add(
                1, {**metric_attributes, "error_type": _error_type_from_job(job)}
            )
        else:
            jobs_succeeded_counter.add(1, metric_attributes)

        # Duration histograms depend on timestamps written by the child to
        # Redis. After a failed refresh those values are stale or absent, so
        # skip rather than record bogus durations.
        if not refresh_ok:
            return

        started_at = getattr(job, "started_at", None)
        ended_at = getattr(job, "ended_at", None)
        created_at = getattr(job, "created_at", None)

        # queue_wait_time: time the job spent in Redis before the child started
        # executing. Preserved from the pre-refactor SLI definition so existing
        # dashboards/alerts keyed on this histogram see no semantic change.
        if started_at and created_at:
            queue_wait_ms = (started_at - created_at).total_seconds() * 1000
            queue_wait_time_histogram.record(queue_wait_ms, metric_attributes)

        if started_at and ended_at:
            processing_time_ms = (ended_at - started_at).total_seconds() * 1000
            processing_time_histogram.record(processing_time_ms, metric_attributes)

        if created_at and ended_at:
            total_time_ms = (ended_at - created_at).total_seconds() * 1000
            total_job_time_histogram.record(total_time_ms, metric_attributes)

    def perform_job(self, job: Job, queue: Queue) -> bool:
        """Job execution body (runs in the forked child after main_work_horse).

        Per-job metric emission moved to `execute_job` in the parent so the
        pod has only one metric exporter; this override now only logs the
        lifecycle.
        """
        func_name = job.func_name if hasattr(job, "func_name") else "unknown"
        logger.debug(
            f"Starting perform_job for job {job.id}, func: {func_name}, queue: {queue.name}"
        )

        try:
            logger.info(
                f"Processing job {job.id} (func={func_name}, queue={queue.name})"
            )
            result = super().perform_job(job, queue)
            logger.info(f"Job '{job.id}' completed successfully")
            return result
        except Exception as e:
            logger.error(
                f"Job '{job.id}' failed: {type(e).__name__}: {e}", exc_info=True
            )
            raise


_EXCEPTION_LINE = re.compile(r"^([A-Za-z_][A-Za-z0-9_.]*)\s*:")


def _error_type_from_job(job: Job) -> str:
    """Best-effort extraction of the exception class name from `job.exc_info`.

    RQ stores the child's traceback as a string. Python's traceback format
    places the exception line at column 0 ("ExceptionClass: message"), with
    any continuation of a multi-line message and stack frames indented. We
    scan lines from the end, skip indented continuations, and match the first
    unindented `Name:` line — that's the outermost exception that propagated
    out (correct under chained-exception traceback printing too).

    Falls back to "UnknownError" if no such line is found.
    """
    exc_info = getattr(job, "exc_info", None)
    if not exc_info:
        return "UnknownError"
    for line in reversed(exc_info.splitlines()):
        if not line or line[0].isspace():
            continue
        m = _EXCEPTION_LINE.match(line)
        if m:
            # Strip dotted module prefix: `requests.exceptions.ConnectionError`
            # -> `ConnectionError`.
            return m.group(1).rsplit(".", 1)[-1] or "UnknownError"
    return "UnknownError"
