"""
RQ Worker Manager for Gunicorn integration.

This module manages the RQ worker lifecycle as a background thread
when the Flask application starts under Gunicorn.
"""

import logging
import sys
import os
import socket
import threading
from typing import Optional

import redis
from opik_backend.utils import redis_utils
from rq import Queue, Worker
from rq.serializers import JSONSerializer

logger = logging.getLogger(__name__)

class RqWorkerManager:
    """
    Manages RQ worker lifecycle with exponential backoff reconnection.
    
    The worker runs in a background thread and automatically reconnects
    if the Redis connection is lost.
    """
    
    def __init__(self):
        """
        Initialize the RQ worker manager.
        
        Args:
            app: Flask application instance
        """
        self.worker_thread: Optional[threading.Thread] = None
        self.should_stop = threading.Event()
        self.redis_conn: Optional[redis.Redis] = None
        self.worker: Optional[Worker] = None
        
        # Queue names to listen to (comma-separated)
        queue_names_str = os.getenv('RQ_QUEUE_NAMES', 'opik:optimizer-cloud')
        self.queue_names = [name.strip() for name in queue_names_str.split(',') if name.strip()]
        
        # Log configuration
        logger.info("RQ Worker Manager Configuration:")
        logger.info(f"  Queue names: {self.queue_names}")
        
    
    def _configure_worker_logger(self, worker: Worker) -> None:
        """
        Configure the RQ worker logger to align with application logging format.
        """
        rq_logger = worker.log  # logger name: 'rq.worker'
        for handler in list(rq_logger.handlers):
            rq_logger.removeHandler(handler)
        formatter = logging.Formatter(
            fmt='[%(asctime)s] [pid=%(process)d/%(processName)s] [thr=%(thread)d/%(threadName)s] [%(levelname)s] [%(name)s] - %(message)s',
            datefmt='%Y-%m-%d %H:%M:%S %z'
        )
        stream_handler = logging.StreamHandler(sys.stderr)
        stream_handler.setFormatter(formatter)
        rq_logger.addHandler(stream_handler)
        rq_logger.setLevel(logging.INFO)
        rq_logger.propagate = False

    def _run_worker(self):
        """
        Run the RQ worker (single-start). Reconnection is delegated to the
        Redis client / RQ internals. Startup performs a single ping health check.
        """
        logger.info("Starting RQ worker manager thread")

        try:
            # Use shared Redis client in binary mode for RQ
            redis_client = redis_utils.get_redis_client()
            redis_client.ping()

            # Import custom worker class
            from opik_backend.rq_worker import MetricsWorker

            # Create queues with JSONSerializer (default RQ contract)
            queues = [
                Queue(name, connection=redis_client, serializer=JSONSerializer())
                for name in self.queue_names
            ]

            logger.info(f"Listening on queues: {self.queue_names}")
            logger.info("Using JSONSerializer and default Job (plain JSON data)")

            # Create and run worker with unique name and custom Job class
            worker = MetricsWorker(
                queues,
                connection=redis_client,
                serializer=JSONSerializer(),
            )
            # Keep reference for graceful shutdown
            self.worker = worker

            # Align RQ worker logger format with application logs
            self._configure_worker_logger(worker)

            # Monkey-patch _install_signal_handlers to do nothing
            worker._install_signal_handlers = lambda: None

            logger.info(f"RQ worker starting (hostname: {socket.gethostname()}, PID: {os.getpid()})")

            # Run worker (blocks until stop requested or error). Reconnects are
            # left to underlying libraries; if it exits, manager won't auto-restart.
            worker.work(
                logging_level=logging.INFO,
                with_scheduler=False
            )

        except Exception as e:
            logger.error(f"Unexpected error in RQ worker: {e}", exc_info=True)
        finally:
            # Do not close shared Redis client here; leave lifecycle to app
            self.worker = None

        logger.info("RQ worker manager thread stopped")
    
    def start(self):
        """
        Start the RQ worker in a background thread.
        
        This is called when the Flask application starts.
        """
        if self.worker_thread and self.worker_thread.is_alive():
            logger.warning("RQ worker thread already running")
            return
        
        logger.info("Starting RQ worker manager")
        self.should_stop.clear()
        
        self.worker_thread = threading.Thread(
            target=self._run_worker,
            name="RqWorkerThread",
            daemon=True  # Daemon thread will stop when main process exits
        )
        self.worker_thread.start()
        
        logger.info("RQ worker manager started successfully")
    
    def stop(self):
        """
        Stop the RQ worker gracefully.
        
        This is called when the Flask application shuts down.
        """
        if not self.worker_thread or not self.worker_thread.is_alive():
            logger.info("RQ worker thread not running")
            return
        
        logger.info("Stopping RQ worker manager")
        self.should_stop.set()
        # Request worker to stop if available
        try:
            if self.worker and hasattr(self.worker, 'request_stop'):
                self.worker.request_stop()
        except Exception:
            logger.warning("Failed to request worker stop", exc_info=True)
        
        # Wait for thread to finish (with timeout)
        self.worker_thread.join(timeout=10)

        # Fallback: second-phase wait with shorter timeout
        if self.worker_thread.is_alive():
            logger.warning("RQ worker thread still running after initial timeout; waiting briefly")
            self.worker_thread.join(timeout=3)
        
        if self.worker_thread.is_alive():
            logger.warning("RQ worker thread did not stop gracefully")
        else:
            logger.info("RQ worker manager stopped successfully")


# Global worker manager instance
_worker_manager: Optional[RqWorkerManager] = None


def init_rq_worker(app=None):
    """
    Initialize and start the RQ worker manager.
    
    Since Gunicorn is configured with --workers 1 in entrypoint.sh,
    there will be only ONE worker process, which means ONE RQ worker
    per Gunicorn application.
    
    Environment Variables:
        RQ_WORKER_ENABLED: Enable/disable RQ worker (default: true)
    
    Args:
        app: Flask application instance (unused; accepted for compatibility)
    """
    global _worker_manager
    
    if _worker_manager is not None:
        logger.warning(f"RQ worker manager already initialized in this process (PID: {os.getpid()})")
        return
    
    logger.info(f"Initializing RQ worker manager (PID: {os.getpid()}, hostname: {socket.gethostname()})")
    _worker_manager = RqWorkerManager()
    _worker_manager.start()
    
    # Register shutdown handler
    import atexit
    atexit.register(shutdown_rq_worker)
    
    logger.info(f"RQ worker manager initialized successfully (PID: {os.getpid()})")


def shutdown_rq_worker():
    """
    Shutdown the RQ worker manager gracefully.
    
    This is called when the application exits.
    """
    global _worker_manager
    
    if _worker_manager is None:
        return
    
    logger.info("Shutting down RQ worker manager")
    _worker_manager.stop()
    _worker_manager = None

