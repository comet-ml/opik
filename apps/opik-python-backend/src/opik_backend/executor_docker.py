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
client = docker.from_env()
executor = concurrent.futures.ThreadPoolExecutor()

class DockerExecutor(CodeExecutorBase):
    def __init__(self):
        super().__init__()
        # Docker-specific configuration
        self.docker_registry = os.getenv("PYTHON_CODE_EXECUTOR_IMAGE_REGISTRY", "ghcr.io/comet-ml/opik")
        self.docker_image = os.getenv("PYTHON_CODE_EXECUTOR_IMAGE_NAME", "opik-sandbox-executor-python")
        self.docker_tag = os.getenv("PYTHON_CODE_EXECUTOR_IMAGE_TAG", "latest")

        self.instance_id = str(uuid7())
        self.container_labels = {"managed_by": self.instance_id}
        self.container_pool = Queue()
        self.pool_lock = Lock()
        self.ensure_pool_filled()
        self.running = True
        atexit.register(self.cleanup)

    def ensure_pool_filled(self):
        if not self.running:
            logger.warning("Executor is shutting down, skipping container creation")
            return

        with self.pool_lock:
            while self.running and len(self.get_managed_containers()) < self.max_parallel:
                logger.warning("Not enough containers running; creating more...")
                self.create_container()

    def get_managed_containers(self):
        return client.containers.list(filters={
            "label": f"managed_by={self.instance_id}",
            "status": "running"
        })

    def create_container(self):
        new_container = client.containers.run(
            image=f"{self.docker_registry}/{self.docker_image}:{self.docker_tag}",
            command=["tail", "-f", "/dev/null"],
            mem_limit="128mb",
            cpu_shares=2,
            detach=True,
            network_disabled=True,
            security_opt=["no-new-privileges"],
            labels=self.container_labels
        )
        self.container_pool.put(new_container)

    def release_container(self, container):
        def async_release():
            try:
                logger.debug(f"Stopping container {container.id}. Will create a new one.")
                container.stop(timeout=1)
                container.remove(force=True)
                self.create_container()
            except Exception as e:
                logger.error(f"Error replacing container: {e}")

        executor.submit(async_release)

    def get_container(self):
        if not self.running:
            raise RuntimeError("Executor is shutting down, no containers available")
            
        while self.running:
            try:
                return self.container_pool.get(timeout=self.exec_timeout)
            except Exception as e:
                if not self.running:
                    raise RuntimeError("Executor is shutting down, no containers available")
                    
                logger.warning(f"Couldn't get a container to execute after waiting for {self.exec_timeout}s. Ensuring we have enough and trying again.")
                self.ensure_pool_filled()

    def run_scoring(self, code: str, data: dict) -> dict:
        if not self.running:
            return {"code": 503, "error": "Service is shutting down"}
            
        container = self.get_container()
        try:
            with concurrent.futures.ThreadPoolExecutor() as exec_pool:
                future = exec_pool.submit(
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
            try:
                container.stop(timeout=1)
                container.remove(force=True)
            except Exception as e:
                logger.error(f"Error cleaning up container {container.id}: {e}")
            
            # Create new container asynchronously
            def async_replace():
                try:
                    self.create_container()
                except Exception as e:
                    logger.error(f"Error creating replacement container: {e}")

            executor.submit(async_replace)

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
