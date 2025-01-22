import json
import logging
import os

import docker

from opik_backend.scoring_commands import PYTHON_SCORING_COMMAND

logger = logging.getLogger(__name__)

PYTHON_CODE_EXECUTOR_IMAGE_REGISTRY = os.getenv("PYTHON_CODE_EXECUTOR_IMAGE_REGISTRY", "ghcr.io/comet-ml/opik")
PYTHON_CODE_EXECUTOR_IMAGE_NAME = os.getenv("PYTHON_CODE_EXECUTOR_IMAGE_NAME", "opik-sandbox-executor-python")
PYTHON_CODE_EXECUTOR_IMAGE_TAG = os.getenv("PYTHON_CODE_EXECUTOR_IMAGE_TAG", "latest")


def run_scoring_in_docker_python_container(code, data):
    client = docker.from_env()
    try:
        # TODO: Optimise run latency e.g: pre-allocating containers
        # Containers runs pulls the image if not available locally
        container = client.containers.run(
            image=f"{PYTHON_CODE_EXECUTOR_IMAGE_REGISTRY}/{PYTHON_CODE_EXECUTOR_IMAGE_NAME}:{PYTHON_CODE_EXECUTOR_IMAGE_TAG}",
            command=["python", "-c", PYTHON_SCORING_COMMAND, code, json.dumps(data)],
            mem_limit="128mb",
            cpu_shares=2,
            detach=True,
            network_disabled=True,
            security_opt=["no-new-privileges"],
        )
        try:
            result = container.wait(timeout=3)
            logs = container.logs().decode("utf-8")
            status_code = result["StatusCode"]
            if status_code == 0:
                last_line = logs.strip().splitlines()[-1]
                # TODO: Validate JSON response e.g: schema validation
                return json.loads(last_line)
            else:
                logging.warn(f"Execution failed (Code: {status_code}):\n{logs}")
                try:
                    last_line = logs.strip().splitlines()[-1]
                    return {"code": 400, "error": json.loads(last_line).get("error")}
                except Exception as e:
                    logger.debug(f"Exception parsing container error logs: {e}")
                    return {"code": 400, "error": "Execution failed: Python code contains an invalid metric"}
        finally:
            container.remove()
    except Exception as e:
        logger.error(f"An unexpected error occurred: {e}")
        return {"code": 500, "error": "An unexpected error occurred"}
