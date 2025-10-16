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
from rq import Worker
from opentelemetry import trace
from opik_backend.jobs.optimizer import process_optimizer_job as _process_optimizer_job
from opik_backend.workers.metrics_worker import MetricsWorker as _MetricsWorker
from opik_backend.workers.death_penalty import NoOpDeathPenalty as _NoOpDeathPenalty

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

def process_optimizer_job(*args, **kwargs):
    # Re-export to preserve import path stability
    return _process_optimizer_job(*args, **kwargs)

class MetricsWorker(_MetricsWorker):
    pass

class NoOpDeathPenalty(_NoOpDeathPenalty):
    pass
