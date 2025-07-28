import asyncio
import atexit
import json
import logging
import os
import threading
import time
from uuid6 import uuid7

import aiodocker
from opentelemetry import metrics

from opik_backend.executor import CodeExecutorBase, ExecutionResult
from opik_backend.scoring_commands import PYTHON_SCORING_COMMAND

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

# Create a gauge metric to track the number of available containers in the pool
container_pool_size_gauge = meter.create_gauge(
    name="container_pool_size",
    description="Number of available containers in the pool queue",
)

container_execution_latency_histogram = meter.create_histogram(
    name="container_execution_latency",
    description="Latency of container execution operations in milliseconds",
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

        self.instance_id = str(uuid7())
        self.container_labels = {"managed_by": self.instance_id}
        self._shutdown_requested = False  # Thread-safe flag for shutdown coordination
        
        # Simple lazy initialization without background threads
        self._initialized = False
        self.client = None
        self.container_pool = None
        self._pool_lock = None

        atexit.register(self.cleanup)

    async def _ensure_initialized(self):
        """Lightweight initialization in the request event loop."""
        if self._initialized:
            return
            
        logger.info("Initializing Docker client in request event loop...")
        
        try:
            # Initialize async components in the current event loop  
            self.container_pool = asyncio.Queue()
            self._pool_lock = asyncio.Lock()
            
            # Create Docker client in current event loop
            self.client = aiodocker.Docker()
            logger.info("Docker client created successfully")
            
            # Pre-warm the container pool
            await self._pre_warm_container_pool()
            
            # Start background monitor (non-blocking)
            self._monitor_task = asyncio.create_task(self._background_monitor())
            logger.info("Background pool monitor started")
            
            self._initialized = True
            logger.info("✅ Docker executor initialization completed!")
            return True
            
        except Exception as e:
            logger.error(f"Failed to initialize DockerExecutor: {e}")
            raise RuntimeError(f"DockerExecutor initialization failed: {e}")

    async def _pre_warm_container_pool(self):
        """
        Pre-warm the container pool by creating all containers in parallel.
        This ensures containers are ready when the service starts.
        """
        logger.info(f"Pre-warming container pool with {self.max_parallel} containers")
        
        try:
            # Create all containers in parallel for better performance
            tasks = [self._async_create_container() for _ in range(self.max_parallel)]
            results = await asyncio.gather(*tasks, return_exceptions=True)
            
            # Count successful container creations
            successful = sum(1 for result in results if not isinstance(result, Exception))
            failed = len(results) - successful
            
            logger.info(f"Container pool pre-warmed: {successful} created, {failed} failed")
            return successful
            
        except Exception as e:
            logger.error(f"Error during container pool pre-warming: {e}")
            return 0

    async def _background_monitor(self):
        """Background task to monitor and maintain the container pool size."""
        logger.info("Starting background pool monitor")
        
        try:
            while not self._shutdown_requested:
                try:
                    await asyncio.sleep(self.pool_check_interval)
                    
                    if self._shutdown_requested:
                        break
                        
                    # Check pool size and add containers if needed
                    await self._maintain_pool_size()
                        
                except asyncio.CancelledError:
                    logger.info("Background monitor cancelled")
                    break
                except Exception as e:
                    logger.error(f"Error in background monitor: {e}")
                    # Continue monitoring despite errors
                    await asyncio.sleep(1)
                    
        except Exception as e:
            logger.error(f"Background monitor failed: {e}")
        finally:
            logger.info("Background monitor stopped")
            return None

    async def _maintain_pool_size(self):
        """Check pool size and create containers if below target."""
        try:
            async with self._pool_lock:
                current_size = self.container_pool.qsize()
                container_pool_size_gauge.set(current_size)
                
                if current_size < self.max_parallel:
                    needed = self.max_parallel - current_size
                    logger.debug(f"Pool below target: {current_size}/{self.max_parallel}, creating {needed} containers")
            
            # Create containers outside the lock to avoid blocking
            if current_size < self.max_parallel:
                needed = self.max_parallel - current_size
                tasks = [self._async_create_container() for _ in range(needed)]
                results = await asyncio.gather(*tasks, return_exceptions=True)
                
                successful = sum(1 for result in results if not isinstance(result, Exception))
                if successful > 0:
                    logger.info(f"Pool maintenance: created {successful} containers")
                    
            return True
            
        except Exception as e:
            logger.error(f"Error maintaining pool size: {e}")
            return False

    async def _create_client_async(self):
        """Create the persistent aiodocker client."""
        self.client = aiodocker.Docker()
        logger.debug(f"Created persistent aiodocker client for event loop {id(asyncio.get_running_loop())}")

    async def _update_container_pool_size_metric(self):
        """Update the container pool size metric with the current number of containers in the pool."""
        # Non-blocking pool size check using asyncio lock
        try:
            async with self._pool_lock:
                pool_size = self.container_pool.qsize()
                container_pool_size_gauge.set(pool_size)
                logger.debug(f"📊 Current container pool size: {pool_size}")
                return pool_size
        except Exception as e:
            # If can't get lock, return 0
            logger.debug(f"Could not acquire lock for pool size metric: {e}")
            return 0

    def _calculate_latency_ms(self, start_time):
        return (time.time() - start_time) * 1000  # Convert to milliseconds

    async def _async_create_container(self):
        # Use persistent client
        return await self._async_create_container_with_client()

    async def _async_get_managed_containers(self):
        """Get list of managed containers using persistent client."""
        try:
            containers = await self.client.containers.list(filters={
                "label": [f"managed_by={self.instance_id}"],
                "status": ["running"]
            })
            return containers
        except Exception as e:
            logger.error(f"Error listing containers: {e}")
            return []

    def cleanup(self):
        """Non-blocking cleanup of Docker resources on shutdown."""
        if self._shutdown_requested:
            return

        logger.info("🧹 Starting cleanup of Docker executor...")
        self._shutdown_requested = True

        # Cancel the background monitor task
        if hasattr(self, '_monitor_task') and not self._monitor_task.done():
            logger.info("Cancelling background monitor task...")
            self._monitor_task.cancel()

        try:
            # Non-blocking pool cleanup - no lock needed at shutdown
            if hasattr(self, 'container_pool') and self.container_pool:
                try:
                    # Clear the pool - at shutdown we don't need to worry about concurrent access
                    while not self.container_pool.empty():
                        self.container_pool.get_nowait()
                    logger.info("📊 Drained container pool")
                except Exception as e:
                    logger.warning(f"Error draining container pool: {e}")
            else:
                logger.info("No container pool to drain")
                
            logger.info("Starting subprocess cleanup...")
            self.cleanup_with_subprocess()
            
        except Exception as e:
            logger.error(f"Error during cleanup: {e}")

        logger.info("✅ Docker executor cleanup completed")

    def cleanup_with_subprocess(self):
        """Force cleanup using a fresh event loop and Docker client."""
        
        logger.info("Starting fresh cleanup for DockerExecutor")
    
        try:
            # Create a completely new event loop for cleanup
            logger.info("Creating fresh event loop for cleanup")
            cleanup_loop = asyncio.new_event_loop()
            asyncio.set_event_loop(cleanup_loop)
            
            # Run the cleanup in the new loop
            cleanup_loop.run_until_complete(self._fresh_cleanup())
            
            # Close the cleanup loop
            cleanup_loop.close()
            logger.info("Fresh cleanup completed successfully")
            
        except Exception as e:
            logger.error(f"Error during fresh cleanup: {e}")

    async def _fresh_cleanup(self):
        """Clean up containers using a fresh Docker client in a fresh event loop."""
        
        logger.info("Starting fresh Docker cleanup with new client")
        
        # Create a fresh Docker client
        fresh_client = None
        try:
            fresh_client = aiodocker.Docker()
            logger.info("Created fresh Docker client")
            
            # Find all containers managed by this executor
            logger.info(f"Looking for containers with label managed_by={self.instance_id}")
            managed_containers = await fresh_client.containers.list(filters={
                "label": [f"managed_by={self.instance_id}"],
                "status": ["running"]
            })
            
            logger.info(f"Found {len(managed_containers)} containers to clean up")
            
            tasks = [self._async_stop_container(container) for container in managed_containers]
            await asyncio.gather(*tasks, return_exceptions=True)
            logger.info(f"Successfully cleaned up containers")
            
        except Exception as e:
            logger.error(f"Error during fresh cleanup: {e}")
        finally:
            # Always close the fresh client
            if fresh_client:
                try:
                    await fresh_client.close()
                    logger.info("Closed fresh Docker client")
                except Exception as e:
                    logger.error(f"Error closing fresh Docker client: {e}")

    async def _async_run_scoring_with_cleanup(self, container, code: str, data: dict, payload_type: str | None = None):
        try:
            # Execute the scoring directly with the container object
            result = await self._async_execute_in_container(container, code, data, payload_type)
            return result
        except Exception as e:
            # This catches any exceptions not handled by the inner method
            logger.error(f"Unexpected error in scoring wrapper: {e}")
            return {"code": 500, "error": "An unexpected error occurred"}
        finally:
            # Handle container cleanup asynchronously within this event loop
            try:
                # Create replacement container first
                await self._async_create_container()
            except Exception as e:
                logger.error(f"Error creating replacement container: {e}")
            
            # Stop old container using container object directly
            try:
                await self._async_stop_container(container)
            except Exception as e:
                logger.error(f"Error stopping container: {e}")

    async def _async_create_container_with_client(self):
        """Create container using persistent client."""
        # Record the start time for detailed container creation metrics
        start_time = time.time()

        config = {
            "Image": f"{self.docker_registry}/{self.docker_image}:{self.docker_tag}",
            "Cmd": ["tail", "-f", "/dev/null"],  # a never ending process so Docker won't kill the container
            "HostConfig": {
                "Memory": 256 * 1024 * 1024,  # 256MB in bytes
                "CpuShares": 2,
                "NetworkMode": "none" if self.network_disabled else "default",
                "SecurityOpt": ["no-new-privileges"],
            },
            "Labels": self.container_labels
        }

        try:
            # Create and start container with timeout
            new_container = await asyncio.wait_for(
                self.client.containers.create(config), 
                timeout=15.0
            )
            await asyncio.wait_for(
                new_container.start(), 
                timeout=10.0
            )

            # Store container object with async lock
            async with self._pool_lock:
                await self.container_pool.put(new_container)

            # Calculate and record the latency for the direct container creation
            latency = self._calculate_latency_ms(start_time)
            container_creation_histogram.record(latency, attributes={"method": "create_container"})
            logger.info(f"✅ Created container {new_container.id[:12]} in {latency:.3f} milliseconds")
            
            return new_container  # Return the created container
            
        except asyncio.TimeoutError:
            logger.error("Container creation timed out")
            raise
        except Exception as e:
            logger.error(f"Failed to create container: {e}")
            raise

    async def _async_execute_in_container(self, container, code: str, data: dict, payload_type: str | None = None):
        start_time = time.time()
        
        try:
            # Step 1: Create exec object
            exec_obj = await asyncio.wait_for(
                container.exec(
                    cmd=["python", "-c", PYTHON_SCORING_COMMAND, code, json.dumps(data), payload_type or ""],
                    stdin=False,
                    tty=False
                ),
                timeout=self.exec_timeout
            )
            
            # Step 2: Start execution and get stream
            stream = exec_obj.start()
            
            # Step 3: Read output from stream (remove timeout wrapper for Flask compatibility)
            stdout_data = b""
            stderr_data = b""
            
            try:
                while True:
                    message = await asyncio.wait_for(stream.read_out(), timeout=self.exec_timeout)
                    if not message:
                        break
                    
                    # message.stream: 1=stdout, 2=stderr
                    if message.stream == 1:
                        stdout_data += message.data
                    elif message.stream == 2:
                        stderr_data += message.data
                        
            except asyncio.TimeoutError:
                await stream.close()
                raise
            except Exception:
                # End of stream or other error
                pass
            finally:
                await stream.close()
            
            result = ExecutionResult(
                exit_code=0,  # aiodocker doesn't provide exit codes directly
                output=stdout_data if stdout_data else b""
            )
            latency = self._calculate_latency_ms(start_time)
            logger.info(f"Execution completed in {latency:.3f} milliseconds")
            
            container_execution_latency_histogram.record(latency, attributes={"method": "run_scoring"})

            return await self.parse_execution_result(result)
        except asyncio.TimeoutError:
            logger.error(f"Execution timed out in container {container.id}")
            return {"code": 504, "error": "Server processing exceeded timeout limit."}
        except Exception as e:
            # Only treat specific timeout-related errors as timeouts
            error_str = str(e).lower()
            elapsed_time = time.time() - start_time
            
            # Consider it a timeout only if:
            # 1. We're close to the timeout duration AND have timeout-related error messages
            # 2. OR if it's an explicit timeout error
            is_timeout_related = any(phrase in error_str for phrase in ["timeout", "timed out"])
            is_near_timeout = elapsed_time >= (self.exec_timeout * 0.8)  # 80% of timeout duration
            
            if (is_timeout_related and is_near_timeout) or "asyncio.timeout" in error_str:
                logger.error(f"Execution timed out in container {container.id}: {e}")
                return {"code": 504, "error": "Server processing exceeded timeout limit."}
            else:
                logger.error(f"An unexpected error occurred in container execution: {e}")
                return {"code": 500, "error": "An unexpected error occurred"}

    async def _async_stop_container(self, container):
        """Stop and delete container object with aggressive cleanup."""
        try:
            container_id = container.id
            logger.info(f"🔥 Force-stopping container {container_id}")
            start_time = time.time()

            # Skip graceful stop, go directly to force delete for speed
            await container.delete(force=True)

            latency = self._calculate_latency_ms(start_time)
            container_stop_histogram.record(latency, attributes={"method": "force_stop_container"})
            logger.info(f"⚡ Force-stopped container {container_id} in {latency:.3f} milliseconds")
        except Exception as e:
            container_id = getattr(container, 'id', 'unknown')[:12]
            logger.error(f"❌ Failed to stop container {container_id}: {e}")

    async def get_container(self):
        """Get a container object from the pool with on-demand creation if needed."""
        max_retries = 3
        retry_count = 0
        
        logger.debug(f"get_container: Starting with retry_count={retry_count}")
        
        while retry_count < max_retries:
            try:
                logger.debug(f"get_container: Loop iteration {retry_count}, acquiring lock...")
                # Try to get container using async context manager
                async with self._pool_lock:
                    logger.debug(f"get_container: Lock acquired, checking pool...")
                    if not self.container_pool.empty():
                        container = await self.container_pool.get()
                        # Update metrics directly without nested lock
                        pool_size = self.container_pool.qsize()
                        container_pool_size_gauge.set(pool_size)
                        logger.info(f"✅ Got container from pool: {container.id[:12]}")
                        return container
                    else:
                        logger.info("Pool is empty, creating container on-demand")
                
                logger.debug(f"get_container: Lock released, creating container...")
                # Pool is empty, create container on-demand (outside the lock to avoid deadlock)
                try:
                    await asyncio.wait_for(self._async_create_container(), timeout=30.0)
                    logger.info("Created container on-demand, checking pool again...")
                    # Don't increment retry_count on successful creation, just continue to check pool
                    continue
                except asyncio.TimeoutError:
                    logger.error("Container creation timed out")
                    retry_count += 1
                    if retry_count >= max_retries:
                        raise RuntimeError("Failed to create container: timeout")
                except Exception as e:
                    logger.error(f"Failed to create container on-demand: {e}")
                    retry_count += 1
                    if retry_count >= max_retries:
                        raise RuntimeError(f"Failed to create container: {e}")
                        
            except Exception as e:
                logger.error(f"Error getting container: {e}")
                retry_count += 1
                if retry_count >= max_retries:
                    raise RuntimeError(f"No available containers after {max_retries} retries: {e}")
            
            # Brief wait before retrying only on failures
            await asyncio.sleep(0.1)
        
        # This should never be reached, but add for safety
        raise RuntimeError("Unexpected end of get_container method")

    async def run_scoring(self, code: str, data: dict, payload_type: str | None = None):
        """
        Run scoring code in a Docker container with proper cleanup.
        Uses the current event loop (Flask's async context).
        """
        start_time = time.time()
        if self._shutdown_requested:
            logger.warning("Executor is shutting down, rejecting new requests")
            return {"code": 503, "error": "Service is shutting down"}
            
        # Ensure initialization happens only once using non-blocking lock
        await self._ensure_initialized()
            
        try:
            # Use Flask's current event loop - get container directly
            container = await self.get_container()
            result = await self._async_run_scoring_with_cleanup(container, code, data, payload_type)
            latency = self._calculate_latency_ms(start_time)
            logger.info(f"run_scoring completed in {latency:.3f} milliseconds")
            return result
        except Exception as e:
            logger.error(f"Error in container execution: {e}")
            return {"code": 500, "error": "An unexpected error occurred"}

