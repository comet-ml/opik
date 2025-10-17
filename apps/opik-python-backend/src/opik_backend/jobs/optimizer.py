import logging
import time
from datetime import datetime, timezone
from rq import get_current_job
from opentelemetry import trace

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)


def _normalize_message_args(*args, **kwargs):
    if args and isinstance(args[0], dict):
        message_data = args[0]
        return (
            message_data.get('message', 'No message'),
            message_data.get('wait_seconds', 0),
        )
    if 'message' in kwargs:
        return (
            kwargs.get('message', 'No message'),
            kwargs.get('wait_seconds', 0),
        )
    return (str(args[0]) if args else 'No message', 0)


def process_optimizer_job(*args, **kwargs):
    """
    Process an optimizer job from the Java backend.
    """
    with tracer.start_as_current_span("process_optimizer_job") as span:
        logger.info(f"Received args: {args}, kwargs: {kwargs}")
        try:
            current_job = get_current_job()
            message_text, wait_seconds = _normalize_message_args(*args, **kwargs)
            span.set_attribute("message", message_text)

            logger.info(f"Processing optimizer job message: {message_text} (wait: {wait_seconds}s)")
            if wait_seconds > 0:
                time.sleep(wait_seconds)

            result = {
                "status": "success",
                "message": f"Optimizer job processed: {message_text}",
                "processed_by": "Python RQ Worker - Optimizer",
                "wait_time": wait_seconds,
                "timestamp": datetime.now(timezone.utc).isoformat(),
            }

            logger.info(f"Optimizer job processed successfully: {result}")
            return result
        except Exception as e:
            logger.error(f"Error processing optimizer job: {e}")
            raise


