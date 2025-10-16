"""
RQ Worker for processing tasks from Redis queues.

This module provides an RQ-based worker that processes jobs created by Java
in RQ's native plain JSON format.

Java-Python Integration:
- Java creates jobs with plain JSON (UTF-8) data field
- Python RQ worker processes jobs natively
- No bridge component needed
- OpenTelemetry metrics track job processing performance
"""

import logging
import time
from datetime import datetime, timezone
from rq import Worker, get_current_job
from opentelemetry import trace
from opentelemetry.metrics import get_meter

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

# OpenTelemetry Metrics
meter = get_meter(__name__)

# Job processing metrics
jobs_processed_counter = meter.create_counter(
    name="rq_worker.jobs.processed",
    description="Total number of jobs processed",
    unit="1"
)

jobs_failed_counter = meter.create_counter(
    name="rq_worker.jobs.failed",
    description="Total number of jobs that failed",
    unit="1"
)

jobs_succeeded_counter = meter.create_counter(
    name="rq_worker.jobs.succeeded",
    description="Total number of jobs that succeeded",
    unit="1"
)

processing_time_histogram = meter.create_histogram(
    name="rq_worker.job.processing_time",
    description="Time spent processing a job (from start to finish)",
    unit="ms"
)

queue_wait_time_histogram = meter.create_histogram(
    name="rq_worker.job.queue_wait_time",
    description="Time job spent waiting in queue (from creation to processing)",
    unit="ms"
)

total_job_time_histogram = meter.create_histogram(
    name="rq_worker.job.total_time",
    description="Total job time (from creation to completion)",
    unit="ms"
)

# ================================
# Death penalty override for threads
# ================================

class NoOpDeathPenalty:
    """
    No-op death penalty to avoid using signals from a non-main thread.
    Used to disable RQ's UnixSignalDeathPenalty when running worker in a background thread.
    """

    def __init__(self, timeout, exception_class, **kwargs):
        # Accept arbitrary kwargs like job_id to match RQ's signature
        self.timeout = timeout
        self.exception_class = exception_class

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        # Do not suppress exceptions
        return False

    def setup_death_penalty(self):
        pass

    def cancel_death_penalty(self):
        pass




def process_hello_world(*args, **kwargs):
    """
    Process a hello world message from the Java backend.
    
    This function is called by RQ when a job is dequeued.
    
    Args:
        *args: Positional arguments (first arg should be dict with message data)
        **kwargs: Keyword arguments
    
    Returns:
        dict: Processing result
    """
    with tracer.start_as_current_span("process_hello_world") as span:
        logger.info(f"Received args: {args}, kwargs: {kwargs}")
        try:
            current_job = get_current_job()
            if current_job:
                logger.info(f"PY job id={current_job.id} func=process_hello_world args={args} kwargs={kwargs}")
        except Exception:
            pass

        # Handle different argument formats
        if args and isinstance(args[0], dict):
            message_data = args[0]
            message_text = message_data.get('message', 'No message')
            wait_seconds = message_data.get('wait_seconds', 0)
        elif 'message' in kwargs:
            message_text = kwargs.get('message', 'No message')
            wait_seconds = kwargs.get('wait_seconds', 0)
        else:
            message_text = str(args[0]) if args else 'No message'
            wait_seconds = 0

        logger.info(f"Processing hello world message: {message_text} (wait: {wait_seconds}s)")

        # Simulate processing time
        if wait_seconds > 0:
            time.sleep(wait_seconds)

        result = {
            "status": "success",
            "message": f"Received and processed: {message_text}",
            "processed_by": "Python RQ Worker",
            "wait_time": wait_seconds,
            "timestamp": datetime.now(timezone.utc).isoformat()
        }

        logger.info(f"Message processed successfully: {result}")
        return result

# ================================
# Custom RQ Worker with Metrics
# ================================

class MetricsWorker(Worker):
    """
    Custom RQ Worker that emits OpenTelemetry metrics.
    Uses HybridSerializer + JavaCompatibleJob for Java-created jobs.
    """
    
    # Disable signal-based death penalty in thread context
    death_penalty_class = NoOpDeathPenalty

    def execute_job(self, job, queue):
        """
        Override execute_job to add debug logging BEFORE perform_job.
        This is called by the worker before perform_job.
        """
        logger.info(f"execute_job called for job {job.id}")
        logger.info(f"Job status: {job.get_status()}")
        try:
            result = super().execute_job(job, queue)
            logger.info(f"execute_job completed successfully")
            return result
        except Exception as e:
            logger.error(f"execute_job FAILED: {type(e).__name__}: {e}")
            logger.error("Full traceback:", exc_info=True)
            raise
    
    def perform_job(self, job, queue):
        """
        Override perform_job to add metrics collection.
        
        Args:
            job: RQ Job instance
            queue: RQ Queue instance
            
        Returns:
            Result of job execution
        """
        # Pre-consume guard no longer needed (Java sets 'func' field). Kept minimal logging below.
        logger.info(f"Starting perform_job for job {job.id}")
        logger.info(f"Job origin: {job.origin if hasattr(job, 'origin') else 'N/A'}")
        logger.info(f"Job func_name: {job.func_name if hasattr(job, 'func_name') else 'N/A'}")
        
        func_name = job.func_name if hasattr(job, 'func_name') else 'unknown'
        queue_name = queue.name
        
        # Calculate queue wait time (created_at to started_at)
        queue_wait_ms = None
        if job.created_at and job.started_at:
            queue_wait_seconds = (job.started_at - job.created_at).total_seconds()
            queue_wait_ms = queue_wait_seconds * 1000
            queue_wait_time_histogram.record(
                queue_wait_ms,
                {"queue": queue_name, "function": func_name}
            )
        
        metric_attributes = {
            "queue": queue_name,
            "function": func_name
        }
        
        try:
            # Execute the job (RQ sets started_at and ended_at internally)
            # Explicit log tying the exact job id to the function and arguments for auditability
            logger.info(
                "Processing job id=%s func=%s",
                getattr(job, 'id', 'unknown'),
                func_name,
            )
            logger.info(f"About to call super().perform_job()")
            result = super().perform_job(job, queue)
            logger.info(f"super().perform_job() returned: {type(result)}")
            
            # Calculate processing time (started_at to ended_at)
            processing_time_ms = None
            if job.started_at and job.ended_at:
                processing_time_seconds = (job.ended_at - job.started_at).total_seconds()
                processing_time_ms = processing_time_seconds * 1000
            
            # Calculate total time (created_at to ended_at)
            total_time_ms = None
            if job.created_at and job.ended_at:
                total_time_seconds = (job.ended_at - job.created_at).total_seconds()
                total_time_ms = total_time_seconds * 1000
            
            # Record success metrics
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
            # Record failure metrics
            logger.error(f"Exception caught in perform_job: {type(e).__name__}")
            logger.error(f"Exception message: {str(e)}")
            logger.error(f"Exception occurred at:", exc_info=True)
            
            jobs_processed_counter.add(1, metric_attributes)
            error_attributes = {
                **metric_attributes,
                "error_type": type(e).__name__
            }
            jobs_failed_counter.add(1, error_attributes)
            
            logger.error(f"Job '{job.id}' failed: {e}", exc_info=True)
            raise
