import logging
import uuid

from rq import Worker
from rq.job import Job
from rq.queue import Queue
from opentelemetry import metrics, trace
from opentelemetry.metrics import get_meter
from opentelemetry.sdk.resources import Resource

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

    Configuration:
        RQ_FAILURE_TTL: TTL for failed jobs in seconds (default: 86400 = 1 day)
    """

    death_penalty_class = NoOpDeathPenalty

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._failure_ttl = RQ_FAILURE_TTL
        logger.info(f"MetricsWorker initialized with failure_ttl={self._failure_ttl}s")

    def main_work_horse(self, job: Job, queue: Queue):
        """RQ post-fork entry point (runs in the forked child process).

        Stamps a fresh `service.instance.id` onto the OTel Resource of the
        already-installed MeterProvider and TracerProvider before any metrics
        fire in the child. Without this, every forked optimizer child inherits
        the parent's identical resource attributes and emits per-process
        runtime metrics (`system_cpu_time_seconds_total`, `system_memory_*`,
        ...) under the same label set as the parent and its siblings.
        Prometheus rejects those batches with HTTP 400
        `duplicate sample for timestamp ... overrides not allowed` the moment
        two children's emit timestamps land in the same millisecond.

        Mutating `_sdk_config.resource` (metrics) and `_resource` (traces) in
        place is deliberately surgical: existing observable instruments
        registered before fork — including `SystemMetricsInstrumentor`'s
        `system.cpu.time`, registered by `opentelemetry-instrument` at process
        start — start emitting under the new resource on the next export tick,
        because the SDK reads the resource at export time rather than at
        instrument-creation time.
        """
        self._stamp_unique_otel_instance_id(job)
        return super().main_work_horse(job, queue)

    @staticmethod
    def _stamp_unique_otel_instance_id(job: Job) -> None:
        instance_id = str(uuid.uuid4())
        addition = Resource.create({"service.instance.id": instance_id})

        meter_provider = metrics.get_meter_provider()
        sdk_cfg = getattr(meter_provider, "_sdk_config", None)
        if sdk_cfg is not None and getattr(sdk_cfg, "resource", None) is not None:
            sdk_cfg.resource = sdk_cfg.resource.merge(addition)

        tracer_provider = trace.get_tracer_provider()
        existing_trace_resource = getattr(tracer_provider, "_resource", None)
        if existing_trace_resource is not None:
            tracer_provider._resource = existing_trace_resource.merge(addition)

        logger.info(
            "Stamped service.instance.id=%s on forked RQ child for job %s",
            instance_id,
            getattr(job, "id", "unknown"),
        )

    def handle_job_failure(self, job: Job, queue: Queue, started_job_registry=None, exc_string=''):
        """Handle job failure with custom failure TTL.

        Override the default failure_ttl (1 year) with a configurable value.
        Signature matches RQ 2.6.0: handle_job_failure(job, queue, started_job_registry, exc_string)
        """
        if job.failure_ttl is None:
            job.failure_ttl = self._failure_ttl
            logger.debug(f"Set failure_ttl={self._failure_ttl}s for job {job.id}")

        return super().handle_job_failure(job, queue, started_job_registry, exc_string)

    def execute_job(self, job: Job, queue: Queue) -> bool:
        """Execute a job and return success status."""
        logger.debug(f"execute_job called for job {job.id}, status: {job.get_status()}")
        try:
            result = super().execute_job(job, queue)
            logger.debug(f"execute_job completed for job {job.id}")
            return result
        except Exception as e:
            logger.error(
                f"execute_job FAILED for job {job.id}: {type(e).__name__}: {e}",
                exc_info=True,
            )
            raise

    def perform_job(self, job: Job, queue: Queue) -> bool:
        logger.debug(
            f"Starting perform_job for job {job.id}, func: {getattr(job, 'func_name', 'unknown')}"
        )

        func_name = job.func_name if hasattr(job, "func_name") else "unknown"
        queue_name = queue.name

        queue_wait_ms = None
        if job.created_at and job.started_at:
            queue_wait_seconds = (job.started_at - job.created_at).total_seconds()
            queue_wait_ms = queue_wait_seconds * 1000
            queue_wait_time_histogram.record(
                queue_wait_ms, {"queue": queue_name, "function": func_name}
            )

        metric_attributes = {"queue": queue_name, "function": func_name}

        # Track concurrent jobs (UpDownCounter is thread-safe)
        concurrent_jobs_counter.add(1, metric_attributes)

        try:
            logger.info(
                f"Processing job {job.id} (func={func_name}, queue={queue_name})"
            )
            result = super().perform_job(job, queue)

            processing_time_ms = None
            if job.started_at and job.ended_at:
                processing_time_seconds = (
                    job.ended_at - job.started_at
                ).total_seconds()
                processing_time_ms = processing_time_seconds * 1000

            total_time_ms = None
            if job.created_at and job.ended_at:
                total_time_seconds = (job.ended_at - job.created_at).total_seconds()
                total_time_ms = total_time_seconds * 1000

            jobs_processed_counter.add(1, metric_attributes)
            jobs_succeeded_counter.add(1, metric_attributes)

            if processing_time_ms is not None:
                processing_time_histogram.record(processing_time_ms, metric_attributes)

            if total_time_ms is not None:
                total_job_time_histogram.record(total_time_ms, metric_attributes)

            logger.info(
                f"Job '{job.id}' completed successfully "
                f"(processing: {processing_time_ms:.2f}ms{f', queue wait: {queue_wait_ms:.2f}ms' if queue_wait_ms else ''})"
            )

            return result
        except Exception as e:
            jobs_processed_counter.add(1, metric_attributes)
            error_attributes = {**metric_attributes, "error_type": type(e).__name__}
            jobs_failed_counter.add(1, error_attributes)

            logger.error(
                f"Job '{job.id}' failed: {type(e).__name__}: {e}", exc_info=True
            )
            raise
        finally:
            # Decrement concurrent jobs counter (UpDownCounter is thread-safe)
            concurrent_jobs_counter.add(-1, metric_attributes)
