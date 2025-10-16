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
import time
from typing import Optional

import redis
from redis.exceptions import ConnectionError as RedisConnectionError
from rq import Queue, Worker
from rq.serializers import JSONSerializer
from rq.job import Job

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
        
        # Configuration from environment
        self.redis_host = os.getenv('REDIS_HOST', 'localhost')
        self.redis_port = int(os.getenv('REDIS_PORT', '6379'))
        self.redis_db = int(os.getenv('REDIS_DB', '0'))
        self.redis_password = os.getenv('REDIS_PASSWORD')
        
        # Queue names to listen to (comma-separated)
        queue_names_str = os.getenv('RQ_QUEUE_NAMES', 'opik:optimizer-cloud')
        self.queue_names = [name.strip() for name in queue_names_str.split(',') if name.strip()]
        
        # Exponential backoff configuration
        self.initial_backoff = float(os.getenv('RQ_INITIAL_BACKOFF', '1'))  # seconds
        self.max_backoff = float(os.getenv('RQ_MAX_BACKOFF', '60'))  # seconds
        self.backoff_multiplier = float(os.getenv('RQ_BACKOFF_MULTIPLIER', '2'))
        self.connection_timeout = float(os.getenv('REDIS_TIMEOUT_SECONDS', '5'))
        self.health_check_interval = int(os.getenv('REDIS_HEALTH_CHECK_INTERVAL_SECONDS', '60'))
        
        # Log configuration
        logger.info("RQ Worker Manager Configuration:")
        logger.info(f"  Redis: {self.redis_host}:{self.redis_port}/{self.redis_db}")
        logger.info(f"  Queue names: {self.queue_names}")
        logger.info(f"  Backoff: initial={self.initial_backoff}s, max={self.max_backoff}s, multiplier={self.backoff_multiplier}")
    
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
    
    def _create_redis_connection(self) -> redis.Redis:
        """
        Create a Redis connection using shared factory.
        """

        # Use the centralized connection factory for consistency
        return redis.Redis(
            host=self.redis_host,
            port=self.redis_port,
            db=self.redis_db,
            password=self.redis_password if self.redis_password else None,
            decode_responses=False,  # RQ handles decoding
            socket_timeout=self.connection_timeout,
            socket_connect_timeout=self.connection_timeout,
            socket_keepalive=True,
            health_check_interval=self.health_check_interval
        )
    
    def _connect_with_backoff(self) -> Optional[redis.Redis]:
        """
        Single-attempt Redis connection with a health-check ping.
        Delegates reconnection behavior to Redis client configuration.
        """
        try:
            conn = self._create_redis_connection()
            conn.ping()
            logger.info("✅ Redis connection established")
            return conn
        except (RedisConnectionError, Exception) as e:
            logger.warning(f"❌ Redis connection failed: {e}")
            return None

    def _run_worker(self):
        """
        Run the RQ worker with automatic reconnection.
        
        This method runs in a background thread and handles:
        - Initial connection with exponential backoff
        - Worker execution
        - Automatic reconnection on connection loss
        """
        logger.info("Starting RQ worker manager thread")
        
        while not self.should_stop.is_set():
            # Connect to Redis with exponential backoff
            self.redis_conn = self._connect_with_backoff()
            
            if self.redis_conn is None:
                # Stop was requested
                logger.info("RQ worker manager stopping (no connection)")
                break
            
            try:
                # Import custom worker class
                from opik_backend.rq_worker import MetricsWorker

                # Create queues with JSONSerializer (default RQ contract)
                queues = [
                    Queue(name, connection=self.redis_conn, serializer=JSONSerializer())
                    for name in self.queue_names
                ]
                
                logger.info(f"Listening on queues: {self.queue_names}")
                logger.info("Using JSONSerializer and default Job (plain JSON data)")
                
                # Create and run worker with unique name and custom Job class
                worker = MetricsWorker(
                    queues,
                    connection=self.redis_conn,
                    serializer=JSONSerializer(),
                )
                # Keep reference for graceful shutdown
                self.worker = worker

                # Align RQ worker logger format with application logs
                self._configure_worker_logger(worker)
                
                # Monkey-patch _install_signal_handlers to do nothing
                # This is required because signal handlers can only be installed in the main thread
                # and our worker runs in a background thread
                worker._install_signal_handlers = lambda: None
                
                logger.info(f"RQ worker starting (hostname: {socket.gethostname()}, PID: {os.getpid()})")
                
                # Run worker (blocks until connection error or stop)
                worker.work(
                    logging_level=logging.INFO,
                    with_scheduler=False  # Disable scheduler to avoid issues
                )
                
            except RedisConnectionError as e:
                logger.error(f"Redis connection lost: {e}. Reconnecting...")
                # Connection lost, loop will reconnect with backoff
            except Exception as e:
                logger.error(f"Unexpected error in RQ worker: {e}", exc_info=True)
                # Wait before reconnecting
                if not self.should_stop.wait(timeout=self.initial_backoff):
                    continue
                else:
                    break
            finally:
                # Clean up connection
                if self.redis_conn:
                    try:
                        self.redis_conn.close()
                    except Exception:
                        logger.error("Error closing Redis connection", exc_info=True)
                    self.redis_conn = None
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
    
    # Check if RQ worker is enabled
    rq_enabled = os.getenv('RQ_WORKER_ENABLED', 'false').lower() == 'true'
    if not rq_enabled:
        logger.info("RQ worker disabled via RQ_WORKER_ENABLED environment variable")
        return
    
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

