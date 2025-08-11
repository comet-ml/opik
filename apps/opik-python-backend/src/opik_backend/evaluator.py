import os
from typing import Any, Dict

from flask import request, abort, jsonify, Blueprint, current_app
from werkzeug.exceptions import HTTPException

from opik_backend.executor import CodeExecutorBase
from opik_backend.http_utils import build_error_response

# Environment variable to control execution strategy
EXECUTION_STRATEGY = os.getenv("PYTHON_CODE_EXECUTOR_STRATEGY", "process")

evaluator = Blueprint('evaluator', __name__, url_prefix='/v1/private/evaluators')

def init_executor(app):
    """Initialize the code executor when the Flask app starts."""
    if EXECUTION_STRATEGY == "docker":
        from opik_backend.executor_docker import DockerExecutor
        app.executor = DockerExecutor()
    elif EXECUTION_STRATEGY == "process":
        from opik_backend.executor_process import ProcessExecutor
        process_executor = ProcessExecutor()
        # start services only in the following case to avoid double initialization in debug mode
        if os.environ.get('WERKZEUG_RUN_MAIN') == 'true' or not app.debug:
            process_executor.start_services()
        app.executor = process_executor
    else:
        raise ValueError(f"Unknown execution strategy: {EXECUTION_STRATEGY}")

def get_executor() -> CodeExecutorBase:
    """Get the executor instance from the Flask app context."""
    return current_app.executor

@evaluator.errorhandler(400)
def bad_request(exception: HTTPException):
    return build_error_response(exception, 400)

@evaluator.errorhandler(500)
def internal_server_error(exception: HTTPException):
    return build_error_response(exception, 500)

@evaluator.route("/python", methods=["POST"])
def execute_evaluator_python():
    if request.method != "POST":
        return

    payload: Any = request.get_json(force=True)

    code: str = payload.get("code")
    if code is None:
        abort(400, "Field 'code' is missing in the request")

    data: Dict[Any, Any] = payload.get("data")
    if data is None:
        abort(400, "Field 'data' is missing in the request")

    # Extract type information for conversation thread metrics
    payload_type = payload.get("type")

    # Get the executor from app context and run the code
    response = get_executor().run_scoring(code, data, payload_type)

    if "error" in response:
        abort(response["code"], response["error"])

    scores = response.get("scores", [])
    if len(scores) == 0:
        current_app.logger.info("Missing ScoreResult in code '%s'", code)
        abort(400, "The provided 'code' field didn't return any 'opik.evaluation.metrics.ScoreResult'")

    return jsonify({"scores": scores})
