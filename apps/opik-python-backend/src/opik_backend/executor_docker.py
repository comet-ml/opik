import atexit
import concurrent.futures
import json
import logging
import os
import time
from queue import Queue
from threading import Lock, Event
from typing import Optional
from uuid6 import uuid7

import docker
import schedule
from opentelemetry import metrics
from opentelemetry import trace

from opik_backend.executor import CodeExecutorBase, ExecutionResult

logger = logging.getLogger(__name__)

# Create a meter for Docker executor metrics
meter = metrics.get_meter("docker_executor")

# Create histogram metrics for container operations
container_creation_histogram = meter.create_histogram(
    name="container_creation_latency",
    description="Latency of container creation operations in milliseconds",
    unit="ms",
)

container_stop_histogram = meter.create_histogram(
    name="container_stop_latency",
    description="Latency of container stop operations in milliseconds",
    unit="ms",
)

scoring_executor_histogram = meter.create_histogram(
    name="scoring_executor_latency",
    description="Latency of scoring executor operations in milliseconds",
    unit="ms",
)

# Create a gauge metric to track the number of available containers in the pool
container_pool_size_gauge = meter.create_gauge(
    name="container_pool_size",
    description="Number of available containers in the pool queue",
)

scoring_executor_queue_size_gauge = meter.create_gauge(
    name="scoring_executor_queue_size",
    description="Number of tasks in the scoring executor queue",
)

get_container_histogram = meter.create_histogram(
    name="get_container_latency",
    description="Latency of getting a container from the pool in milliseconds",
    unit="ms",
)

class DockerExecutor(CodeExecutorBase):
    def __init__(self):
        super().__init__()
        # Docker-specific configuration
        self.docker_registry = os.getenv("PYTHON_CODE_EXECUTOR_IMAGE_REGISTRY", "ghcr.io/comet-ml/opik")
        self.docker_image = os.getenv("PYTHON_CODE_EXECUTOR_IMAGE_NAME", "opik-sandbox-executor-python")
        self.docker_tag = os.getenv("PYTHON_CODE_EXECUTOR_IMAGE_TAG", "latest")
        self.pool_check_interval = int(os.getenv("PYTHON_CODE_EXECUTOR_POOL_CHECK_INTERVAL_IN_SECONDS", "3"))
        self.network_disabled = os.getenv("PYTHON_CODE_EXECUTOR_ALLOW_NETWORK", "false").lower() != "true"

        self.client = docker.from_env()
        self.instance_id = str(uuid7())
        self.container_labels = {"managed_by": self.instance_id}
        self.container_pool = Queue()
        self.pool_lock = Lock()
        self.releaser_executor = concurrent.futures.ThreadPoolExecutor()
        self.scoring_executor = concurrent.futures.ThreadPoolExecutor(max_workers=self.max_parallel)

        self.stop_event = Event()

        # Pre-warm the container pool
        self._pre_warm_container_pool()

        # Start the pool monitor
        self._start_pool_monitor()

        self.tracer = trace.get_tracer(__name__)

        atexit.register(self.cleanup)

    def _start_pool_monitor(self):
        """Start a background thread that periodically checks and fills the container pool."""
        logger.info(f"Starting container pool monitor with {self.pool_check_interval} second interval")

        # Schedule the pool check to run at the configured interval
        schedule.every(self.pool_check_interval).seconds.do(self._check_pool)

        # Start a background thread to run the scheduler
        self.releaser_executor.submit(self._run_scheduler)

    def _check_pool(self):
        """Check and fill the container pool if needed."""
        if self.stop_event.is_set():
            logger.info("Container pool monitor stopped")
            return schedule.CancelJob  # Cancel this job

        try:
            # Update the container pool size metric
            self._update_container_pool_size_metric()

            self.ensure_pool_filled()
            return None  # Continue the job
        except Exception as e:
            logger.error(f"Error in pool monitor: {e}")
            return None  # Continue the job despite the error

    def _run_scheduler(self):
        """Run the scheduler in a background thread."""
        logger.info("Starting scheduler for container pool monitoring")
        while not self.stop_event.is_set():
            schedule.run_pending()
            time.sleep(1)  # Sleep to avoid busy-waiting

        logger.info("Scheduler finished running")

    def _pre_warm_container_pool(self):
        """
        Pre-warm the container pool by creating all containers in parallel.
        This ensures containers are ready when the service starts.
        """
        logger.info(f"Pre-warming container pool with {self.max_parallel} containers")
        # Submit container creation tasks in parallel
        with concurrent.futures.ThreadPoolExecutor(max_workers=self.max_parallel) as pool_init:
            futures = [pool_init.submit(self.create_container) for _ in range(self.max_parallel)]
            # Wait for all containers to be created
            concurrent.futures.wait(futures)

    def ensure_pool_filled(self):
        if self.stop_event.is_set():
            logger.warning("Executor is shutting down, skipping container creation")
            return

        with self.pool_lock:
            while not self.stop_event.is_set() and len(self.get_managed_containers()) < self.max_parallel:
                logger.info("Not enough python runner containers running; creating more...")
                self.create_container()

    def get_managed_containers(self):
        return self.client.containers.list(filters={
            "label": f"managed_by={self.instance_id}",
            "status": "running"
        })

    def _update_container_pool_size_metric(self):
        """Update the container pool size metric with the current number of containers in the pool."""
        pool_size = self.container_pool.qsize()
        container_pool_size_gauge.set(pool_size)
        logger.debug(f"Current container pool size: {pool_size}")
        return pool_size

    def _calculate_latency_ms(self, start_time):
        return (time.time() - start_time) * 1000  # Convert to milliseconds

    def create_container(self):
        # Record the start time for detailed container creation metrics
        start_time = time.time()
        with self.tracer.start_as_current_span("docker.create_container"):
            
            new_container = self.client.containers.run(
                image=f"{self.docker_registry}/{self.docker_image}:{self.docker_tag}",
                command=["tail", "-f", "/dev/null"], # a never ending process so Docker won't kill the container
                mem_limit="256mb",
                cpu_shares=2,
                detach=True,
                network_disabled=self.network_disabled,
                security_opt=["no-new-privileges"],
                labels=self.container_labels
            )

            # Add the container to the pool
            self.container_pool.put(new_container)

            # Calculate and record the latency for the direct container creation
            latency = self._calculate_latency_ms(start_time)
            container_creation_histogram.record(latency, attributes={"method": "create_container"})

            logger.info(f"Created container, id '{new_container.id}' in {latency:.3f} milliseconds")

    def release_container(self, container):
        self.releaser_executor.submit(self.async_release, container)

    def async_release(self, container):
        with self.tracer.start_as_current_span("async_release"):
            # First, ensure we have enough containers in the pool
            self._create_new_container()

            # Now stop and remove the old container
            self._stopContainer(container)

    def _create_new_container(self):
        try:
            # Create the container
            self.create_container()
        except Exception as e:
            logger.error(f"Error replacing container: {e}")

    def _stopContainer(self, container):
        with self.tracer.start_as_current_span("docker.stop_container"):
            try:
                logger.info(f"Stopping container {container.id}. Will create a new one.")

                # Record the start time
                start_time = time.time()

                # Remove the container
                container.remove(force=True)

                # Calculate and record the latency
                latency = self._calculate_latency_ms(start_time)
                container_stop_histogram.record(latency, attributes={"method": "stop_container"})

                logger.info(f"Stopped container {container.id} in {latency:.3f} milliseconds")

            except docker.errors.APIError as e:
                logger.error(f"Container {container.id} failed to be removed")
            except Exception as e:
                logger.error(f"Failed to stop container {container.id}: {e}")

    def get_container(self):
        with self.tracer.start_as_current_span("get_container"):
            if self.stop_event.is_set():
                raise RuntimeError("Executor is shutting down, no containers available")

            while not self.stop_event.is_set():
                try:
                    return self.container_pool.get(timeout=self.exec_timeout)
                except Exception as e:
                    if self.stop_event.is_set():
                        raise RuntimeError("Executor is shutting down, no containers available")

                    logger.warning(f"Couldn't get a container to execute after waiting for {self.exec_timeout}s. Will retry: {e}")

    def run_scoring(self, code: str, data: dict, payload_type: Optional[str] = None) -> dict:
        if self.stop_event.is_set():
            return {"code": 503, "error": "Service is shutting down"}
        
        start_time = time.time()
        container = self.get_container()
        latency = self._calculate_latency_ms(start_time)
        logger.info(f"Get container latency: {latency:.3f} milliseconds")
        get_container_histogram.record(latency, attributes={"method": "get_container"})

        with self.tracer.start_as_current_span("docker.run_scoring"):
            try:
                cmd = ["python", "/opt/opik-sandbox-executor-python/scoring_runner.pyc", code, json.dumps(data), payload_type or ""]
            
                future = self.scoring_executor.submit(
                    container.exec_run,
                    cmd=cmd,
                    detach=False,
                    stdin=False,
                    tty=False
                )

                result = future.result(timeout=self.exec_timeout)
                exec_result = ExecutionResult(
                    exit_code=result.exit_code,
                    output=result.output
                )
                latency = self._calculate_latency_ms(start_time)
                logger.info(f"Scoring executor latency: {latency:.3f} milliseconds")

                # Access ThreadPoolExecutor's internal work queue (private attribute)
                queue_size = self.scoring_executor._work_queue.qsize()
                scoring_executor_queue_size_gauge.set(queue_size)

                scoring_executor_histogram.record(latency, attributes={"method": "run_scoring"})
                return self.parse_execution_result(exec_result)
            except concurrent.futures.TimeoutError:
                logger.error(f"Execution timed out in container {container.id}")
                return {"code": 504, "error": "Server processing exceeded timeout limit."}
            except Exception as e:
                logger.error(f"An unexpected error occurred: {e}")
                return {"code": 500, "error": "An unexpected error occurred"}
            finally:
                # Stop and remove the container, then create a new one asynchronously
                self.release_container(container)
            
    def cleanup(self):
        """Clean up all containers managed by this executor."""
        logger.info("Shutting down Docker executor")
        self.stop_event.set()

        # Clear all scheduled jobs
        schedule.clear()

        # Clean up containers
        while not self.container_pool.empty():
            container = self.container_pool.get(timeout=self.exec_timeout)
            self._stopContainer(container)

        for container in self.get_managed_containers():
            logger.info(f"Cleaning up untracked container {container.id}")
            self._stopContainer(container)

        # Shutdown the executor
        logger.info("Shutting down executor")
        self.releaser_executor.shutdown(wait=False, cancel_futures=True)
        self.scoring_executor.shutdown(wait=False, cancel_futures=True)
