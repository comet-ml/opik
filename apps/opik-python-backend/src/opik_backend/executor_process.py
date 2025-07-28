import asyncio
import atexit
import logging
import os
import signal
import time
import uuid

from multiprocessing import Process, Pipe
import threading

from opentelemetry import metrics

from opik_backend.executor import CodeExecutorBase
from opik_backend import process_worker

logger = logging.getLogger(__name__)

# OTel metrics setup
meter = metrics.get_meter("process_executor")
process_creation_histogram = meter.create_histogram(
    name="process_creation_latency",
    description="Latency of process creation operations in milliseconds",
    unit="ms",
)
process_execution_histogram = meter.create_histogram(
    name="process_execution_latency", 
    description="Latency of code execution in process in milliseconds",
    unit="ms",
)
process_pool_size_gauge = meter.create_gauge(
    name="process_pool_size",
    description="Number of available processes in the pool queue",
)


def _calculate_latency_ms(start_time):
    return (time.time() - start_time) * 1000


def terminate_worker(worker):
    """Synchronous worker termination function."""
    worker_id = worker.get('id', 'unknown')
    process = worker.get('process')
    if not process:
        return

    # Close the connection if it exists and is open
    connection = worker.get('connection')
    if connection:
        try:
            connection.close()
        except Exception as e:
            logger.warning(f"Error closing connection for worker {worker_id}: {e}")

    if not process.is_alive():
        logger.info(f"Worker {worker_id} (PID: {process.pid if process.pid else 'N/A'}) already terminated.")
        return

    logger.info(f"Terminating worker {worker_id} (PID: {process.pid}).")
    try:
        process.terminate()  # Send SIGTERM
        process.join(timeout=2)
        if process.is_alive():  # Check if it's still alive after timeout
            logger.warning(
                f"Worker {worker_id} (PID: {process.pid}) did not terminate gracefully after SIGTERM, killing.")
            process.kill()  # Send SIGKILL
            process.join(timeout=1)  # Wait for SIGKILL to take effect
    except Exception as e:  # Catch any other exceptions during termination
        logger.error(f"Exception during termination of worker {worker_id} (PID: {process.pid}): {e}")
        if process.is_alive():
            process.kill()
            process.join(timeout=1)

    logger.info(f"Termination sequence for worker {worker_id} complete.")


class ProcessExecutor(CodeExecutorBase):
    def __init__(self):
        super().__init__()
        self.pool_check_interval = int(os.getenv("PYTHON_CODE_EXECUTOR_POOL_CHECK_INTERVAL_IN_SECONDS", "3"))
        self._shutdown_requested = False
        self.instance_id = str(uuid.uuid4())
        
        # Process tracking for cleanup
        self._spawned_pids = set()
        
        # Defer async components to post_init
        self.loop = None
        self.process_pool = None
        self._monitor_task = None
        
        # Lazy initialization tracking (non-blocking)
        self._initialized = False
        
        # Note: No atexit registration - cleanup handled by explicit calls only to avoid blocking
        
    async def post_init(self):
        """Initializes async components after the executor is created."""
        logger.info("Running ProcessExecutor post_init...")
        self.loop = asyncio.get_running_loop()
        self.process_pool = []  # Simple list - completely thread-safe with non-blocking lock
        self._pool_lock = threading.RLock()  # Reentrant lock for non-blocking operations
        
        logger.info(f"Pre-warming process pool with {self.max_parallel} processes")
        await self._pre_warm_process_pool()
        
        logger.info(f"Starting background pool monitor with {self.pool_check_interval} second interval")
        self._monitor_task = asyncio.create_task(self._pool_monitor_loop())
        
        logger.info("ProcessExecutor post_init completed.")


            

    def _handle_shutdown_signal(self, signum, frame):
        """Handle shutdown signals (SIGINT, SIGTERM) in a non-blocking way."""
        try:
            signal_name = signal.Signals(signum).name

            signal.signal(signal.SIGINT, signal.SIG_DFL)
            signal.signal(signal.SIGTERM, signal.SIG_DFL)
            
            # Prevent re-entry if shutdown is already in progress
            if self._shutdown_requested:
                logger.warning(f"ProcessExecutor: received signal {signal_name}, but shutdown already in progress. Ignoring.")
                return

            logger.warning(f"ProcessExecutor: received signal {signal_name}. Initiating immediate cleanup.")
            
            # Call non-blocking cleanup
            self.cleanup()
            
            logger.warning(f"ProcessExecutor: Signal cleanup finished.")
        except Exception as e:
            logger.error(f"Error in signal handler: {e}")

    async def _ensure_initialized(self):
        """Ensure ProcessExecutor is initialized only once (non-blocking)."""
        import logging
        logger = logging.getLogger(__name__)
        logger.info(f"🔍 _ensure_initialized called: _initialized={self._initialized}, loop={self.loop}")
        
        if not self._initialized:  # Only check _initialized flag
            logger.info("🚀 ProcessExecutor: Performing lazy initialization...")
            try:
                await self.post_init()
                self._initialized = True
                logger.info("✅ ProcessExecutor: Lazy initialization completed successfully!")
            except Exception as e:
                logger.error(f"❌ ProcessExecutor: Initialization failed: {e}")
                import traceback
                logger.error(f"🔍 Traceback: {traceback.format_exc()}")
                raise
        else:
            logger.info(f"⏭️ ProcessExecutor already initialized: _initialized={self._initialized}")

    async def _pre_warm_process_pool(self):
        """Pre-warm the process pool with workers."""
        import logging
        logger = logging.getLogger(__name__)
        logger.info(f"🔥 Starting pre-warm with {self.max_parallel} workers...")
        
        # Create workers concurrently
        create_tasks = [
            asyncio.create_task(self._async_create_worker_process())
            for _ in range(self.max_parallel)
        ]
        
        logger.info(f"🚀 Created {len(create_tasks)} worker creation tasks")
        results = await asyncio.gather(*create_tasks, return_exceptions=True)
        
        successful = sum(1 for r in results if not isinstance(r, Exception))
        failed = len(results) - successful
        
        logger.info(f"📊 Worker creation complete: {successful} successful, {failed} failed")
        
        # NON-BLOCKING pool size check for logging
        actual_pool_size = 0
        if self._pool_lock.acquire(blocking=False):
            try:
                actual_pool_size = len(self.process_pool)
            finally:
                self._pool_lock.release()
        logger.info(f"🎯 Final pool size: {actual_pool_size}")

    async def _pool_monitor_loop(self):
        """Monitor pool and maintain worker count."""
        if self._shutdown_requested:
            return

        try:
            await asyncio.sleep(self.pool_check_interval)
            await self._ensure_pool_filled()
        except Exception as e:
            logger.error(f"Error in pool monitor loop: {e}")

    async def _ensure_pool_filled(self):
        """Ensure pool has enough workers - NON-BLOCKING."""
        if self._shutdown_requested:
            return

        try:
            current_pool_size = 0
            
            # NON-BLOCKING size check
            if self._pool_lock.acquire(blocking=False):
                try:
                    current_pool_size = len(self.process_pool)
                finally:
                    self._pool_lock.release()
            else:
                # If we can't get the lock, skip this check - it's non-critical
                return
            
            if current_pool_size < self.max_parallel:
                workers_needed = self.max_parallel - current_pool_size
                logger.info(f"Pool needs {workers_needed} workers to reach max_parallel {self.max_parallel} (current total: {current_pool_size}). Creating...")
                
                # Create workers concurrently
                create_tasks = [
                    asyncio.create_task(self._async_create_worker_process())
                    for _ in range(workers_needed)
                ]
                await asyncio.gather(*create_tasks, return_exceptions=True)

        except Exception as e:
            logger.error(f"Error ensuring pool filled: {e}")

    async def _async_create_worker_process(self):
        """Create a single worker process asynchronously with non-blocking pool operations."""
        worker_id = str(uuid.uuid4())[:8]
        start_time = time.time()
        
        try:
            parent_conn, child_conn = Pipe()
            
            process = Process(
                target=process_worker.worker_process_main,
                args=(child_conn,),
                daemon=True
            )
            process.start()
            self._spawned_pids.add(process.pid)
            
            # Non-blocking wait for worker ready signal
            ready_signal = await self.loop.run_in_executor(None, parent_conn.recv)
            
            if ready_signal != "READY":
                raise Exception(f"Worker {worker_id} (PID: {process.pid}) sent unexpected signal: {ready_signal}")

            worker = {'id': worker_id, 'process': process, 'connection': parent_conn}
            
            # NON-BLOCKING pool addition
            if self._pool_lock.acquire(blocking=False):
                try:
                    self.process_pool.append(worker)
                finally:
                    self._pool_lock.release()
            else:
                # If we can't acquire lock immediately, skip this worker
                logger.warning(f"Could not add worker {worker_id} to pool - lock busy (non-blocking)")
                process.terminate()
                return
            
            latency = _calculate_latency_ms(start_time)
            process_creation_histogram.record(latency)
            logger.info(f"Created worker {worker_id} (PID: {process.pid}) in {latency:.3f}ms.")
            
        except Exception as e:
            logger.error(f"Failed to create worker process {worker_id}: {e}")
            if 'parent_conn' in locals():
                parent_conn.close()
            if 'child_conn' in locals():
                child_conn.close()

    async def _async_get_worker(self):
        """Get a worker from the pool with timeout - COMPLETELY NON-BLOCKING."""
        if self._shutdown_requested:
            raise RuntimeError("Executor is shutting down")
            
        try:
            # NON-BLOCKING get with timeout simulation
            start_time = time.time()
            worker = None
            
            while time.time() - start_time < self.exec_timeout:
                # NON-BLOCKING lock attempt
                if self._pool_lock.acquire(blocking=False):
                    try:
                        if len(self.process_pool) > 0:
                            worker = self.process_pool.pop(0)  # Get first worker
                    finally:
                        self._pool_lock.release()
                    
                    if worker:
                        break
                
                await asyncio.sleep(0.001)  # Minimal delay - 1ms instead of 10ms for better responsiveness
            
            if worker is None:
                raise asyncio.TimeoutError("No available workers in the pool.")
            
            if not worker['process'].is_alive():
                logger.warning(f"Got a dead worker {worker['id']} from pool. Retrying.")
                # Don't terminate dead worker - just retry
                return await self._async_get_worker()  # Retry
                
            return worker
            
        except asyncio.TimeoutError:
            logger.error("Timeout getting a worker from the pool.")
            raise

    async def run_scoring(self, code: str, data: dict, payload_type: str | None = None) -> dict:
        """
        Run scoring code in a worker process with proper cleanup.
        Uses the current event loop (Flask's async context).
        """
        import logging
        logger = logging.getLogger(__name__)
        logger.info("🎯 run_scoring called - starting process...")
        
        if self._shutdown_requested:
            logger.warning("Executor is shutting down, rejecting new requests")
            return {"code": 503, "error": "Service is shutting down"}
            
        # Ensure initialization happens only once using non-blocking lock
        logger.info("🔄 Ensuring ProcessExecutor is initialized...")
        await self._ensure_initialized()
        
        # NON-BLOCKING pool size check for logging
        pool_size = 0
        if self._pool_lock.acquire(blocking=False):
            try:
                pool_size = len(self.process_pool)
            finally:
                self._pool_lock.release()
        logger.info(f"📊 Current pool size: {pool_size}")
            
        worker = None
        try:
            logger.info("🔍 Getting worker from pool...")
            worker = await self._async_get_worker()
            worker_id = worker.get('id', 'unknown')
            logger.info(f"✅ Got worker: {worker_id}")
            connection = worker.get('connection')
            
            if not connection:
                raise Exception(f"Worker {worker_id} has no connection object.")

            start_exec_time = time.time()
            
            # Send work to worker
            await self.loop.run_in_executor(
                None,
                connection.send,
                {'code': code, 'data': data, 'payload_type': payload_type}
            )

            # Wait for result with timeout
            result = await asyncio.wait_for(
                self.loop.run_in_executor(None, connection.recv),
                timeout=self.exec_timeout
            )

            latency = _calculate_latency_ms(start_exec_time)
            process_execution_histogram.record(latency)

            # Return worker to pool - NON-BLOCKING
            if self._pool_lock.acquire(blocking=False):
                try:
                    self.process_pool.append(worker)
                finally:
                    self._pool_lock.release()
            else:
                # If we can't return the worker immediately, kill it to avoid resource leak
                logger.warning(f"Could not return worker {worker_id} to pool - lock busy, terminating worker")
                try:
                    os.kill(worker['process'].pid, signal.SIGKILL)
                except (ProcessLookupError, OSError):
                    pass
            
            return result
            
        except asyncio.TimeoutError:
            logger.error(f"Timeout waiting for result from worker {worker.get('id') if worker else 'N/A'}")
            if worker:
                # Immediate kill without blocking
                process = worker.get('process')
                if process and process.is_alive():
                    try:
                        os.kill(process.pid, signal.SIGKILL)
                    except (ProcessLookupError, OSError):
                        pass
            return {"code": 500, "error": "Execution timed out"}
            
        except Exception as e:
            logger.error(f"Error in run_scoring with worker {worker.get('id') if worker else 'N/A'}: {e}")
            if worker:
                # Immediate kill without blocking
                process = worker.get('process')
                if process and process.is_alive():
                    try:
                        os.kill(process.pid, signal.SIGKILL)
                    except (ProcessLookupError, OSError):
                        pass
            return {"code": 500, "error": f"Failed to execute code: {e}"}

    def cleanup(self):
        """Completely non-blocking cleanup - immediate process termination."""
        if self._shutdown_requested:
            return
            
        self._shutdown_requested = True

        try:
            # Fire-and-forget cleanup: kill processes immediately with SIGKILL
            pids_killed = 0
            if self.process_pool is not None:
                # NON-BLOCKING cleanup - get all workers without waiting
                workers_to_kill = []
                
                # Try to get all workers with non-blocking lock
                if self._pool_lock.acquire(blocking=False):
                    try:
                        workers_to_kill = list(self.process_pool)  # Copy all workers
                        self.process_pool.clear()  # Clear the pool
                    finally:
                        self._pool_lock.release()
                
                # Kill workers without holding any locks
                for worker in workers_to_kill:
                    try:
                        process = worker.get('process')
                        if process and process.is_alive():
                            os.kill(process.pid, signal.SIGKILL)  # Immediate kill
                            pids_killed += 1
                    except (ProcessLookupError, OSError):
                        pass  # Process already gone
                        
            logger.info(f"Immediately killed {pids_killed} worker processes (completely non-blocking)")
                
        except Exception:
            pass  # Ignore all errors during cleanup to avoid blocking
