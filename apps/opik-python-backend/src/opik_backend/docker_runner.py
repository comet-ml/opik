import atexit
import concurrent.futures
import json
import logging
import os
from queue import Queue
from threading import Lock
from uuid6 import uuid7  # or uuid6 if preferred

import docker

from opik_backend.scoring_commands import PYTHON_SCORING_COMMAND

IMAGE_REGISTRY = os.getenv("PYTHON_CODE_EXECUTOR_IMAGE_REGISTRY", "ghcr.io/comet-ml/opik")
IMAGE_NAME = os.getenv("PYTHON_CODE_EXECUTOR_IMAGE_NAME", "opik-sandbox-executor-python")
IMAGE_TAG = os.getenv("PYTHON_CODE_EXECUTOR_IMAGE_TAG", "latest")
PRELOADED_CONTAINERS = int(os.getenv("PYTHON_CODE_EXECUTOR_CONTAINERS_NUM", 5))
EXEC_TIMEOUT = int(os.getenv("PYTHON_CODE_EXECUTOR_EXEC_TIMEOUT_IN_SECS", 3))

logger = logging.getLogger(__name__)

client = docker.from_env()
container_pool = Queue()
container_pool_creation_lock = Lock()
executor = concurrent.futures.ThreadPoolExecutor()

instance_id = str(uuid7())
container_labels={
    "managed_by": instance_id,
}

def preload_containers():
    if not container_pool.empty():
        logger.warning("Containers already preloaded. Skipping duplicate initialization")
        return

    logger.info(f"Preloading {PRELOADED_CONTAINERS} containers...")
    ensure_container_pool_filled()
    # remove containers when application ends
    atexit.register(cleanup_containers)

def running_containers():
    return client.containers.list(filters={
        "label": f"managed_by={instance_id}",
        "status": "running"
    })

def ensure_container_pool_filled():
    with container_pool_creation_lock:
        while len(running_containers()) < PRELOADED_CONTAINERS:
            logger.info("Not enough python runner containers running; creating more...")
            create_container()

def cleanup_containers():
    # First: remove containers from the pool
    while not container_pool.empty():
        try:
            container = container_pool.get(timeout=EXEC_TIMEOUT)
            logger.info(f"Stopping and removing container {container.id}")
            container.stop(timeout=1)
            container.remove(force=True)
        except Exception as e:
            logger.error(f"Failed to remove container from pool due to {e}. Retrying.")

    # Then: find and clean up any zombie running containers owned by this instance
    for container in running_containers():
        try:
            logger.info(f"Cleaning up untracked container {container.id}")
            container.stop(timeout=1)
            container.remove(force=True)
        except Exception as e:
            logger.error(f"Failed to remove zombie container {container.id}: {e}")

def create_container():
    new_container = client.containers.run(
        image=f"{IMAGE_REGISTRY}/{IMAGE_NAME}:{IMAGE_TAG}",
        command=["tail", "-f", "/dev/null"], # a never ending process so Docker wont kill the container
        mem_limit="128mb",
        cpu_shares=2,
        detach=True,
        network_disabled=True,
        security_opt=["no-new-privileges"],
        labels=container_labels
    )
    container_pool.put(new_container)

def release_container(container):
    def async_release():
        try:
            logger.info(f"Stopping container {container.id}. Will create a new one.")
            container.stop(timeout=1)
            container.remove(force=True)
            create_container()
        except Exception as e:
            logger.error(f"Error replacing container: {e}")

    executor.submit(async_release)

def get_container():
    while True:
        try:
            return container_pool.get(timeout=EXEC_TIMEOUT)
        except Exception as e:
            logger.warning(f"Couldn't get a container to execute after waiting for {EXEC_TIMEOUT}s. Ensuring we have enough and trying again: {e}")
            ensure_container_pool_filled()

def run_scoring_in_docker_python_container(code, data):
    def execute_command():
        return container.exec_run(
            cmd=["python", "-c", PYTHON_SCORING_COMMAND, code, json.dumps(data)],
            detach=False,
            stdin=False,
            tty=False,
        )

    container = get_container()
    try:
        # Run exec_run() with a timeout using ThreadPoolExecutor
        with concurrent.futures.ThreadPoolExecutor() as executor:
            future = executor.submit(execute_command)
            exec_result = future.result(timeout=EXEC_TIMEOUT)  # Enforce timeout for execution

            logs = exec_result.output.decode("utf-8")
            status_code = exec_result.exit_code

            if status_code == 0:
                last_line = logs.strip().splitlines()[-1]
                return json.loads(last_line)
            else:
                logger.warning(f"Execution failed (Code: {status_code}):\n{logs}")
                try:
                    last_line = logs.strip().splitlines()[-1]
                    return {"code": 400, "error": json.loads(last_line).get("error")}
                except Exception as e:
                    logger.info(f"Exception parsing container error logs: {e}")
                    return {"code": 400, "error": "Execution failed: Python code contains an invalid metric"}
    except concurrent.futures.TimeoutError:
        logger.error(f"Execution timed out in container {container.id}")
        return {"code": 504, "error": "Server processing exceeded timeout limit."}
    except Exception as e:
        logger.error(f"An unexpected error occurred: {e}")
        return {"code": 500, "error": "An unexpected error occurred"}
    finally:
        # async replace container
        release_container(container)
