import atexit
import concurrent.futures
import json
import logging
import os
import time
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

        # Container recycling configuration
        self.max_container_uses = int(os.getenv("PYTHON_CODE_EXECUTOR_MAX_CONTAINER_USES", 50))
        self.container_usage_counts = {}  # Track usage count per container

        self.instance_id = str(uuid7())
        self.container_labels = {"managed_by": self.instance_id}
        self.container_pool = Queue()
        self.pool_lock = Lock()
        self.releaser_executor = concurrent.futures.ThreadPoolExecutor()
        self.running = True
        
        # Pre-warm the container pool by creating all containers in parallel
        logger.info(f"Pre-warming container pool with {self.max_parallel} containers")
        with concurrent.futures.ThreadPoolExecutor(max_workers=self.max_parallel) as pool_init:
            # Submit container creation tasks in parallel
            futures = [pool_init.submit(self.create_container) for _ in range(self.max_parallel)]
            # Wait for all containers to be created
            concurrent.futures.wait(futures)
        
        logger.info(f"Container pool initialized with {self.container_pool.qsize()} containers")
        atexit.register(self.cleanup)

    def ensure_pool_filled(self):
        if not self.running:
            logger.warning("Executor is shutting down, skipping container creation")
            return

        with self.pool_lock:
            while self.running and len(self.get_managed_containers()) < self.max_parallel:
                logger.info("Not enough python runner containers running; creating more...")
                self.create_container()

    def get_managed_containers(self):
        return client.containers.list(filters={
            "label": f"managed_by={self.instance_id}",
            "status": "running"
        })

    def create_container(self):
        # Start time measurement for container creation
        start_time = time.time()
        
        # Create the container with optimized settings
        new_container = client.containers.run(
            image=f"{self.docker_registry}/{self.docker_image}:{self.docker_tag}",
            command=["tail", "-f", "/dev/null"], # a never ending process so Docker won't kill the container
            mem_limit="256mb",
            cpu_shares=2,
            detach=True,
            network_disabled=True,
            security_opt=["no-new-privileges"],
            labels=self.container_labels
        )
        
        # Pre-initialize the container by running a simple Python command
        # This ensures Python interpreter is loaded and ready
        try:
            pre_init = self.run_in_container(
                new_container,
                ["python", "-c", "print('Container pre-initialization complete')"],
                timeout=10  # Longer timeout for initialization
            )
            if pre_init.exit_code != 0:
                logger.warning(f"Container {new_container.id} pre-initialization failed: {pre_init.output}")
        except Exception as e:
            logger.warning(f"Error during container {new_container.id} pre-initialization: {e}")
        
        # Add to the pool
        self.container_pool.put(new_container)
        
        # Log creation time for monitoring
        creation_time = time.time() - start_time
        logger.info(f"Created container, id '{new_container.id}' in {creation_time:.2f}s")

    def run_in_container(self, container, cmd, timeout=None):
        """
        Run a command in a container using ThreadPoolExecutor for better timeout handling.
        
        Args:
            container: Docker container to run the command in
            cmd: Command to execute
            timeout: Execution timeout in seconds (defaults to self.exec_timeout)
            
        Returns:
            ExecResult object with exit_code and output
        
        Raises:
            concurrent.futures.TimeoutError: If execution exceeds timeout
        """
        if timeout is None:
            timeout = self.exec_timeout
            
        with concurrent.futures.ThreadPoolExecutor() as executor:
            future = executor.submit(
                container.exec_run,
                cmd=cmd,
                detach=False,
                stdin=False,
                tty=False
            )
            return future.result(timeout=timeout)
    
    def clean_container(self, container):
        """
        Clean up a container's environment to prepare it for reuse.
        
        Args:
            container: The Docker container to clean
            
        Returns:
            bool: True if cleanup was successful, False otherwise
        """
        try:
            # Run cleanup commands to ensure container is in a clean state
            cleanup_cmd = [
                "bash", "-c", 
                "rm -rf /tmp/* 2>/dev/null || true; "  # Remove temp files
                "python -c 'import gc; gc.collect()' 2>/dev/null || true"  # Force garbage collection
            ]
            
            # Use a shorter timeout for cleanup
            cleanup_result = self.run_in_container(container, cleanup_cmd, timeout=5)
            if cleanup_result.exit_code != 0:
                logger.warning(f"Container {container.id} cleanup failed: {cleanup_result.output}")
                return False
            return True
        except Exception as e:
            logger.warning(f"Error during container {container.id} cleanup: {e}")
            return False
            
    def release_container(self, container):
        def async_release():
            try:
                logger.info(f"Stopping container {container.id}. Will create a new one.")
                container.stop(timeout=1)
                container.remove(force=True)
                # Remove from usage tracking
                if container.id in self.container_usage_counts:
                    del self.container_usage_counts[container.id]
                # Create a replacement container
                self.create_container()
            except Exception as e:
                logger.error(f"Error replacing container: {e}")

        self.releaser_executor.submit(async_release)

    def get_container(self):
        if not self.running:
            raise RuntimeError("Executor is shutting down, no containers available")
            
        while self.running:
            try:
                container = self.container_pool.get(timeout=self.exec_timeout)
                
                # Simple health check - verify container is running
                try:
                    container.reload()
                    if container.status != "running":
                        logger.warning(f"Container {container.id} is not running (status: {container.status}), replacing it")
                        self.release_container(container)
                        continue
                    
                    # Perform a simple health check by running a basic command
                    try:
                        health_check = self.run_in_container(container, ["echo", "health_check"], timeout=5)
                        if health_check.exit_code != 0:
                            logger.warning(f"Container {container.id} failed health check, replacing it")
                            self.release_container(container)
                            continue
                    except Exception as e:
                        logger.warning(f"Container {container.id} health check failed with exception: {e}, replacing it")
                        self.release_container(container)
                        continue
                    
                    # Container is healthy, return it
                    return container
                except Exception as e:
                    logger.warning(f"Error checking container {container.id} status: {e}, replacing it")
                    self.release_container(container)
                    continue
                    
            except Exception as e:
                if not self.running:
                    raise RuntimeError("Executor is shutting down, no containers available")
                    
                logger.warning(f"Couldn't get a container to execute after waiting for {self.exec_timeout}s. Ensuring we have enough and trying again: {e}")
                self.ensure_pool_filled()

    def manage_container_after_execution(self, container, success, execution_time):
        """
        Handle container management after code execution.
        This method is intended to be called in a finally block.
        
        Args:
            container: The Docker container used for execution
            success: Boolean indicating if execution was successful
            execution_time: Time taken for execution in seconds
        """
        if success:
            # Increment usage count
            if container.id not in self.container_usage_counts:
                self.container_usage_counts[container.id] = 1
            else:
                self.container_usage_counts[container.id] += 1
            
            # Check if container should be recycled based on usage count
            if self.container_usage_counts[container.id] >= self.max_container_uses:
                logger.info(f"Container {container.id} reached max uses ({self.max_container_uses}), recycling")
                self.release_container(container)
            else:
                # Clean the container before returning it to the pool
                if self.clean_container(container):
                    # Put the container back in the pool for reuse
                    self.container_pool.put(container)
                    logger.info(f"Container {container.id} cleaned and returned to pool for reuse (use {self.container_usage_counts[container.id]}/{self.max_container_uses})")
                else:
                    # If cleanup failed, release the container
                    logger.warning(f"Container {container.id} cleanup failed, recycling it")
                    self.release_container(container)
            
            logger.info(f"Execution completed successfully in {execution_time:.2f}s")
        else:
            # Replace the container if there was an error
            logger.warning(f"Container {container.id} had an error, replacing it")
            self.release_container(container)
    
    def handle_timeout_error(self, container):
        """
        Perform emergency cleanup for timeout cases.
        
        Args:
            container: The Docker container that timed out
        """
        try:
            # Short timeout for emergency cleanup
            self.run_in_container(
                container,
                ["bash", "-c", "pkill -9 -f python 2>/dev/null || true"],
                timeout=2
            )
        except Exception as cleanup_error:
            logger.warning(f"Error during emergency cleanup after timeout: {cleanup_error}")
    
    def run_scoring(self, code: str, data: dict) -> dict:
        if not self.running:
            return {"code": 503, "error": "Service is shutting down"}
            
        container = None
        start_time = time.time()
        success = False
        result = None
        
        try:
            container = self.get_container()
            
            # Use our run_in_container method for better execution management
            cmd = ["python", "-c", PYTHON_SCORING_COMMAND, code, json.dumps(data)]
            result = self.run_in_container(container, cmd)
            
            exec_result = ExecutionResult(
                exit_code=result.exit_code,
                output=result.output
            )
            
            # Mark as successful if exit code is 0
            success = (result.exit_code == 0)
            
            # Return result immediately
            return self.parse_execution_result(exec_result)
                
        except concurrent.futures.TimeoutError:
            execution_time = time.time() - start_time
            logger.error(f"Execution timed out after {execution_time:.2f}s")
            
            if container:
                # Perform emergency cleanup but don't wait for it
                self.handle_timeout_error(container)
                
            return {"code": 504, "error": "Server processing exceeded timeout limit."}
            
        except Exception as e:
            execution_time = time.time() - start_time
            logger.error(f"An unexpected error occurred after {execution_time:.2f}s: {e}")
            return {"code": 500, "error": "An unexpected error occurred"}
            
        finally:
            # Handle container management in the finally block
            if container:
                execution_time = time.time() - start_time
                self.manage_container_after_execution(container, success, execution_time)

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
