import logging
from rq import Worker
from opentelemetry.metrics import get_meter
from .death_penalty import NoOpDeathPenalty

logger = logging.getLogger(__name__)

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


class MetricsWorker(Worker):
    """
    Custom RQ Worker that emits OpenTelemetry metrics.
    """

    death_penalty_class = NoOpDeathPenalty

    def execute_job(self, job, queue):
        logger.info(f"execute_job called for job {job.id}")
        logger.info(f"Job status: {job.get_status()}")
        try:
            result = super().execute_job(job, queue)
            logger.info("execute_job completed successfully")
            return result
        except Exception as e:
            logger.error(f"execute_job FAILED: {type(e).__name__}: {e}")
            logger.error("Full traceback:", exc_info=True)
            raise

    def perform_job(self, job, queue):
        logger.info(f"Starting perform_job for job {job.id}")
        logger.info(f"Job origin: {job.origin if hasattr(job, 'origin') else 'N/A'}")
        logger.info(f"Job func_name: {job.func_name if hasattr(job, 'func_name') else 'N/A'}")

        func_name = job.func_name if hasattr(job, 'func_name') else 'unknown'
        queue_name = queue.name

        queue_wait_ms = None
        if job.created_at and job.started_at:
            queue_wait_seconds = (job.started_at - job.created_at).total_seconds()
            queue_wait_ms = queue_wait_seconds * 1000
            queue_wait_time_histogram.record(
                queue_wait_ms, {"queue": queue_name, "function": func_name}
            )

        metric_attributes = {"queue": queue_name, "function": func_name}

        try:
            logger.info("Processing job id=%s func=%s", getattr(job, 'id', 'unknown'), func_name)
            logger.info("About to call super().perform_job()")
            result = super().perform_job(job, queue)
            logger.info(f"super().perform_job() returned: {type(result)}")

            processing_time_ms = None
            if job.started_at and job.ended_at:
                processing_time_seconds = (job.ended_at - job.started_at).total_seconds()
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
            logger.error(f"Exception caught in perform_job: {type(e).__name__}")
            logger.error(f"Exception message: {str(e)}")
            logger.error("Exception occurred at:", exc_info=True)

            jobs_processed_counter.add(1, metric_attributes)
            error_attributes = {**metric_attributes, "error_type": type(e).__name__}
            jobs_failed_counter.add(1, error_attributes)

            logger.error(f"Job '{job.id}' failed: {e}", exc_info=True)
            raise


