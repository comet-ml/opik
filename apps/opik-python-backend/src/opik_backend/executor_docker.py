import atexit
import concurrent.futures
import json
import logging
import os
from queue import Queue
from threading import Lock
from uuid6 import uuid7

import docker

from opik_backend.executor import CodeExecutorBase, ExecutionResult
from opik_backend.scoring_commands import PYTHON_SCORING_COMMAND

logger = logging.getLogger(__name__)

class DockerExecutor(CodeExecutorBase):
    def __init__(self):
        super().__init__()
        # Docker-specific configuration
        self.docker_registry = os.getenv("PYTHON_CODE_EXECUTOR_IMAGE_REGISTRY", "ghcr.io/comet-ml/opik")
        self.docker_image = os.getenv("PYTHON_CODE_EXECUTOR_IMAGE_NAME", "opik-sandbox-executor-python")
        self.docker_tag = os.getenv("PYTHON_CODE_EXECUTOR_IMAGE_TAG", "latest")

        self.client = docker.from_env()
        self.instance_id = str(uuid7())
        self.container_labels = {"managed_by": self.instance_id}
        self.container_pool = Queue()
        self.pool_lock = Lock()
        self.releaser_executor = concurrent.futures.ThreadPoolExecutor()
        self.running = True
        self.ensure_pool_filled()

        # Pre-warm the container pool
        self._pre_warm_container_pool()
        atexit.register(self.cleanup)

    def _pre_warm_container_pool(self):
        """
        Pre-warm the container pool by creating all containers in parallel.
        This ensures containers are ready when the service starts.
        """
        logger.info(f"Pre-warming container pool with {self.max_parallel} containers")
        with concurrent.futures.ThreadPoolExecutor(max_workers=self.max_parallel) as pool_init:
            # Submit container creation tasks in parallel
            futures = [pool_init.submit(self.create_container) for _ in range(self.max_parallel)]
            # Wait for all containers to be created
            concurrent.futures.wait(futures)

    def ensure_pool_filled(self):
        if not self.running:
            logger.warning("Executor is shutting down, skipping container creation")
            return

        with self.pool_lock:
            while self.running and len(self.get_managed_containers()) < self.max_parallel:
                logger.info("Not enough python runner containers running; creating more...")
                self.create_container()

    def get_managed_containers(self):
        return self.client.containers.list(filters={
            "label": f"managed_by={self.instance_id}",
            "status": "running"
        })

    def create_container(self):
        new_container = self.client.containers.run(
            image=f"{self.docker_registry}/{self.docker_image}:{self.docker_tag}",
            command=["tail", "-f", "/dev/null"], # a never ending process so Docker won't kill the container
            mem_limit="256mb",
            cpu_shares=2,
            detach=True,
            network_disabled=True,
            security_opt=["no-new-privileges"],
            labels=self.container_labels
        )
        self.container_pool.put(new_container)
        logger.info(f"Created container, id '{new_container.id}'")

    def release_container(self, container):
        self.releaser_executor.submit(self.async_release, container)

    def async_release(self, container):
        try:
            # First, ensure we have enough containers in the pool
            self._create_new_container()

            # Now stop and remove the old container
            self._stopContainers(container)
        except Exception as e:
            logger.error(f"Error replacing container: {e}")

    def _create_new_container(self):
        try:
            self.create_container()
        except Exception as e:
            logger.error(f"Error replacing container: {e}")

    def _stopContainers(self, container):
        try:
            logger.info(f"Stopping container {container.id}. Will create a new one.")
            container.stop(timeout=1)
            container.remove(force=True)
            logger.info(f"Stopped container {container.id}")
        except Exception as e:
            logger.error(f"Failed to create new container: {e}")

    def get_container(self):
        if not self.running:
            raise RuntimeError("Executor is shutting down, no containers available")
            
        while self.running:
            try:
                return self.container_pool.get(timeout=self.exec_timeout)
            except Exception as e:
                if not self.running:
                    raise RuntimeError("Executor is shutting down, no containers available")
                    
                logger.warning(f"Couldn't get a container to execute after waiting for {self.exec_timeout}s. Ensuring we have enough and trying again: {e}")
                self.ensure_pool_filled()

    def run_scoring(self, code: str, data: dict) -> dict:
        if not self.running:
            return {"code": 503, "error": "Service is shutting down"}
            
        container = self.get_container()
        try:
            with concurrent.futures.ThreadPoolExecutor() as command_executor:
                future = command_executor.submit(
                    container.exec_run,
                    cmd=["python", "-c", PYTHON_SCORING_COMMAND, code, json.dumps(data)],
                    detach=False,
                    stdin=False,
                    tty=False
                )

                result = future.result(timeout=self.exec_timeout)
                exec_result = ExecutionResult(
                    exit_code=result.exit_code,
                    output=result.output
                )               
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
        self.running = False
        """Clean up all containers managed by this executor."""
        while not self.container_pool.empty():
            try:
                container = self.container_pool.get(timeout=self.exec_timeout)
                logger.info(f"Stopping and removing container {container.id}")
                container.stop(timeout=1)
                container.remove(force=True)
            except Exception as e:
                logger.error(f"Failed to remove container from pool due to {e}")

        for container in self.get_managed_containers():
            try:
                logger.info(f"Cleaning up untracked container {container.id}")
                container.stop(timeout=1)
                container.remove(force=True)
            except Exception as e:
                logger.error(f"Failed to remove zombie container {container.id}: {e}")
