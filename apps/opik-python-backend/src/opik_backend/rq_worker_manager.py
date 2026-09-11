"""
RQ Worker Manager for Gunicorn integration.

This module manages the RQ worker lifecycle as background threads
when the Flask application starts under Gunicorn.

Environment Variables (for self-hosted deployments):
    OPTSTUDIO_MAX_CONCURRENT_JOBS: Number of parallel optimization workers (default: 5)
    RQ_QUEUE_NAMES: Comma-separated queue names to listen to (default: opik:optimizer-cloud)
    RQ_WORKER_ENABLED: Enable/disable RQ worker (default: true)
"""

import logging
import sys
import os
import socket
import threading
from typing import List, Optional

import redis.exceptions
from opik_backend.utils import redis_utils
from rq import Queue, Worker
from rq.serializers import JSONSerializer

logger = logging.getLogger(__name__)

# Environment variable names
ENV_RQ_WORKER_ENABLED = "RQ_WORKER_ENABLED"
ENV_RQ_QUEUE_NAMES = "RQ_QUEUE_NAMES"
ENV_MAX_CONCURRENT_JOBS = "OPTSTUDIO_MAX_CONCURRENT_JOBS"

# Default values
DEFAULT_QUEUE_NAME = "opik:optimizer-cloud"
DEFAULT_MAX_CONCURRENT_JOBS = 5

# Redis key constants
RQ_WORKERS_SET_KEY = "rq:workers"
RQ_WORKER_KEY_PREFIX = "rq:worker:"


class WorkerThread:
    """
    Encapsulates an RQ worker and its associated thread.

    Lifecycle:
        1. Created with index
        2. thread is set immediately after creation
        3. thread.start() runs _run_worker() which sets worker

    Attributes:
        index: Unique identifier for this worker thread
        thread: The Python thread running the RQ worker
        worker: The RQ Worker instance (set after thread starts)
    """

    def __init__(self, index: int) -> None:
        """Initialize a WorkerThread with the given index."""
        self.index: int = index
        self.thread: Optional[threading.Thread] = None
        self.worker: Optional[Worker] = None

    def is_alive(self) -> bool:
        """Check if the thread is still running."""
        return self.thread is not None and self.thread.is_alive()

    def request_stop(self) -> None:
        """Request the RQ worker to stop processing new jobs."""
        if self.worker and hasattr(self.worker, "request_stop"):
            self.worker.request_stop()

    def join(self, timeout: float) -> None:
        """Wait for the thread to finish.

        Args:
            timeout: Maximum time to wait in seconds
        """
        if self.thread:
            self.thread.join(timeout=timeout)


class RqWorkerManager:
    """
    Manages RQ worker lifecycle with parallel job processing.

    Spawns multiple worker threads to process jobs concurrently.
    Each worker thread runs an independent RQ worker that competes
    for jobs from the same queue(s).

    Configuration:
        OPTSTUDIO_MAX_CONCURRENT_JOBS: Number of parallel workers (default: 5)
        RQ_QUEUE_NAMES: Comma-separated queue names to listen to
    """

    def __init__(self) -> None:
        """Initialize the RQ worker manager."""
        self.worker_threads: List[WorkerThread] = []
        self.should_stop: threading.Event = threading.Event()

        # Queue names to listen to (comma-separated)
        queue_names_str: str = os.getenv(ENV_RQ_QUEUE_NAMES, DEFAULT_QUEUE_NAME)
        self.queue_names: List[str] = [
            name.strip() for name in queue_names_str.split(",") if name.strip()
        ]

        # Number of concurrent workers (minimum 1)
        self.max_concurrent_jobs: int = max(
            1, int(os.getenv(ENV_MAX_CONCURRENT_JOBS, str(DEFAULT_MAX_CONCURRENT_JOBS)))
        )

        # Log configuration
        logger.info("RQ Worker Manager Configuration:")
        logger.info(f"  Queue names: {self.queue_names}")
        logger.info(f"  Max concurrent jobs: {self.max_concurrent_jobs}")

    def _cleanup_stale_workers(self) -> None:
        """Clean up stale worker registrations from this process on startup.

        When containers restart without graceful shutdown, old worker registrations
        remain in Redis. This method cleans up workers that belong to our
        hostname AND our PID - avoiding interference with other processes.

        In Docker/containerized environments, PIDs are often reused (e.g., PID 9),
        so we must clean up any workers with our prefix before starting new ones.
        """
        try:
            redis_client = redis_utils.get_redis_client()
            hostname = socket.gethostname()
            current_pid = os.getpid()
            our_prefix = f"{hostname}-{current_pid}-"

            # Get all registered workers
            all_workers = Worker.all(connection=redis_client)
            our_workers = [
                w for w in all_workers if w.name and w.name.startswith(our_prefix)
            ]

            if our_workers:
                logger.info(
                    f"Found {len(our_workers)} stale worker registrations for {our_prefix}*, cleaning up..."
                )
                for worker in our_workers:
                    worker_key = f"{RQ_WORKER_KEY_PREFIX}{worker.name}"
                    try:
                        # Remove from workers set first
                        redis_client.srem(RQ_WORKERS_SET_KEY, worker_key)
                        # Delete the worker hash
                        redis_client.delete(worker_key)
                        # Try register_death for any additional cleanup
                        try:
                            worker.register_death()
                        except redis.exceptions.ResponseError:
                            # Worker was already cleaned up from Redis
                            logger.debug(f"Worker {worker.name} already cleaned up")
                        logger.info(f"Cleaned up stale worker: {worker.name}")
                    except redis.exceptions.RedisError as e:
                        # Redis connectivity or protocol error - log and continue
                        logger.warning(
                            f"Redis error cleaning up worker {worker.name}: {e}"
                        )
            else:
                logger.debug(f"No stale workers found for prefix {our_prefix}")

        except Exception as e:
            logger.warning(f"Failed to cleanup stale workers: {e}")

    def _configure_worker_logger(self, worker: Worker) -> None:
        """Configure the RQ worker logger to align with application logging format.

        Args:
            worker: The RQ Worker instance to configure
        """
        rq_logger = worker.log  # logger name: 'rq.worker'
        for handler in list(rq_logger.handlers):
            rq_logger.removeHandler(handler)
        formatter = logging.Formatter(
            fmt="[%(asctime)s] [pid=%(process)d/%(processName)s] [thr=%(thread)d/%(threadName)s] [%(levelname)s] [%(name)s] - %(message)s",
            datefmt="%Y-%m-%d %H:%M:%S %z",
        )
        stream_handler = logging.StreamHandler(sys.stderr)
        stream_handler.setFormatter(formatter)
        rq_logger.addHandler(stream_handler)
        rq_logger.setLevel(logging.INFO)
        rq_logger.propagate = False

    def _run_worker(self, worker_thread: WorkerThread) -> None:
        """Run a single RQ worker thread.

        Each worker thread operates independently and competes for jobs
        from the shared queue(s). This enables parallel job processing.

        Args:
            worker_thread: The WorkerThread instance to populate with the RQ worker
        """
        thread_name = f"RqWorker-{worker_thread.index}"
        logger.info(f"Starting RQ worker thread: {thread_name}")

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

            logger.info(f"[{thread_name}] Listening on queues: {self.queue_names}")

            # Create worker with unique name including index
            worker = MetricsWorker(
                queues,
                connection=redis_client,
                serializer=JSONSerializer(),
                name=f"{socket.gethostname()}-{os.getpid()}-{worker_thread.index}",
            )

            # Associate worker with its thread for graceful shutdown
            worker_thread.worker = worker

            # Align RQ worker logger format with application logs
            self._configure_worker_logger(worker)

            # Monkey-patch _install_signal_handlers to do nothing
            worker._install_signal_handlers = lambda: None

            logger.info(
                f"[{thread_name}] RQ worker starting (hostname: {socket.gethostname()}, PID: {os.getpid()})"
            )

            # Run worker (blocks until stop requested or error)
            worker.work(logging_level=logging.INFO, with_scheduler=False)

        except Exception as e:
            logger.error(
                f"[{thread_name}] Unexpected error in RQ worker: {e}", exc_info=True
            )

        logger.info(f"[{thread_name}] RQ worker thread stopped")

    def start(self) -> None:
        """Start multiple RQ worker threads for parallel job processing.

        This is called when the Flask application starts.
        Spawns OPTSTUDIO_MAX_CONCURRENT_JOBS worker threads.
        """
        if self.worker_threads and any(wt.is_alive() for wt in self.worker_threads):
            logger.warning("RQ worker threads already running")
            return

        # Clean up any stale worker registrations from previous container runs
        self._cleanup_stale_workers()

        logger.info(
            f"Starting RQ worker manager with {self.max_concurrent_jobs} parallel workers"
        )
        self.should_stop.clear()
        self.worker_threads = []

        for i in range(self.max_concurrent_jobs):
            wt = WorkerThread(index=i)
            thread = threading.Thread(
                target=self._run_worker,
                args=(wt,),
                name=f"RqWorkerThread-{i}",
                daemon=True,  # Daemon threads will stop when main process exits
            )
            wt.thread = thread
            self.worker_threads.append(wt)
            thread.start()

        logger.info(
            f"RQ worker manager started successfully with {self.max_concurrent_jobs} workers"
        )

    def stop(self) -> None:
        """Stop all RQ worker threads gracefully.

        This is called when the Flask application shuts down.
        """
        active_workers = [wt for wt in self.worker_threads if wt.is_alive()]
        if not active_workers:
            logger.info("No RQ worker threads running")
            return

        logger.info(
            f"Stopping RQ worker manager ({len(active_workers)} active workers)"
        )
        self.should_stop.set()

        # Request all workers to stop
        for wt in active_workers:
            try:
                wt.request_stop()
            except Exception:
                logger.warning(
                    f"Failed to request worker {wt.index} stop", exc_info=True
                )

        # Wait for all threads to finish (with timeout)
        for wt in active_workers:
            wt.join(timeout=10)

        # Check for threads that didn't stop
        still_running = [wt for wt in self.worker_threads if wt.is_alive()]
        if still_running:
            logger.warning(
                f"{len(still_running)} RQ worker threads did not stop gracefully"
            )
            # Second-phase wait with shorter timeout
            for wt in still_running:
                wt.join(timeout=3)

        final_running = [wt for wt in self.worker_threads if wt.is_alive()]
        if final_running:
            logger.warning(
                f"{len(final_running)} RQ worker threads still running after timeout"
            )
        else:
            logger.info("RQ worker manager stopped successfully")

        self.worker_threads = []


# Global worker manager instance
_worker_manager: Optional[RqWorkerManager] = None


def init_rq_worker(app: Optional[object] = None) -> None:
    """Initialize and start the RQ worker manager with parallel workers.

    Spawns multiple RQ worker threads (controlled by OPTSTUDIO_MAX_CONCURRENT_JOBS)
    to process optimization jobs in parallel. Each worker operates independently
    and competes for jobs from the shared queue.

    Environment Variables:
        RQ_WORKER_ENABLED: Enable/disable RQ worker (default: true)
        OPTSTUDIO_MAX_CONCURRENT_JOBS: Number of parallel workers (default: 5)
        RQ_QUEUE_NAMES: Comma-separated queue names (default: opik:optimizer-cloud)

    Args:
        app: Flask application instance (unused; accepted for compatibility)
    """
    global _worker_manager

    if _worker_manager is not None:
        logger.warning(
            f"RQ worker manager already initialized in this process (PID: {os.getpid()})"
        )
        return

    logger.info(
        f"Initializing RQ worker manager (PID: {os.getpid()}, hostname: {socket.gethostname()})"
    )
    _worker_manager = RqWorkerManager()
    _worker_manager.start()

    # Register shutdown handler
    import atexit

    atexit.register(shutdown_rq_worker)

    logger.info(f"RQ worker manager initialized successfully (PID: {os.getpid()})")


def shutdown_rq_worker() -> None:
    """Shutdown the RQ worker manager gracefully.

    This is called when the application exits.
    """
    global _worker_manager

    if _worker_manager is None:
        return

    logger.info("Shutting down RQ worker manager")
    _worker_manager.stop()
    _worker_manager = None
