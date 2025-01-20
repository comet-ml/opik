import io
import json
import logging

import docker

from opik_backend.scoring_commands import PYTHON_SCORING_COMMAND

logger = logging.getLogger(__name__)

PYTHON_CODE_EXECUTOR_IMAGE_NAME_AND_TAG = "opik-executor-sandbox-python:latest"

# TODO: Optimise Dockerfile definition e.g: use physical file
PYTHON_CODE_EXECUTOR_DOCKERFILE = """
FROM python:3.12.3-slim
RUN pip install opik
"""


def create_docker_image(dockerfile_string, image_name):
    client = docker.from_env()
    try:
        _, logs = client.images.build(
            fileobj=io.BytesIO(dockerfile_string.encode('utf-8')),
            tag=image_name
        )
        for log in logs:
            logger.info(log.get('stream', '').strip())
        logger.info(f"Image '{image_name}' created successfully.")
    except Exception as e:
        logger.error(f"Error building image '{image_name}': {e}")
        raise e


def run_scoring_in_docker_python_container(code, data):
    client = docker.from_env()
    try:
        # TODO: Optimise run latency e.g: pre-allocating containers
        container = client.containers.run(
            image=PYTHON_CODE_EXECUTOR_IMAGE_NAME_AND_TAG,
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
