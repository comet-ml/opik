import os
from typing import Any, Dict

from flask import request, abort, jsonify, Blueprint, current_app
from werkzeug.exceptions import HTTPException

from opik_backend.executor import CodeExecutorBase
from opik_backend.http_utils import build_error_response
from opik_backend.payload_types import PayloadType

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


@evaluator.route("/common-metrics", methods=["GET"])
def list_common_metrics():
    """List all available common metrics with their metadata."""
    from opik_backend.common_metrics import get_common_metrics_list

    metrics = get_common_metrics_list()
    return jsonify({"content": metrics})


@evaluator.route("/common-metrics/<metric_id>/score", methods=["POST"])
def execute_common_metric(metric_id: str):
    """
    Execute a common metric by its ID in an isolated environment.

    The metric execution runs in an isolated environment using the same executor
    infrastructure as user-supplied Python code (ProcessExecutor or DockerExecutor).

    Expected payload:
    {
        "init_config": { ... },      // Optional: __init__ parameters
        "scoring_kwargs": { ... }    // Required: score method parameters
    }
    """
    from opik_backend.common_metrics import find_metric_class

    metric_cls = find_metric_class(metric_id)
    if metric_cls is None:
        abort(404, f"Unknown metric: {metric_id}")

    payload = request.get_json(force=True)

    init_config = payload.get("init_config", {})
    scoring_kwargs = payload.get("scoring_kwargs")

    if scoring_kwargs is None:
        abort(400, "Field 'scoring_kwargs' is missing in the request")

    if not isinstance(scoring_kwargs, dict):
        abort(400, "Field 'scoring_kwargs' must be an object")

    if init_config is not None and not isinstance(init_config, dict):
        abort(400, "Field 'init_config' must be an object")

    try:
        # Execute metric in isolated environment using the executor infrastructure
        # Pack the common metric data and use COMMON_METRIC payload type
        data = {
            "metric_id": metric_id,
            "init_config": init_config,
            "scoring_kwargs": scoring_kwargs,
        }
        result = get_executor().run_scoring("", data, PayloadType.COMMON_METRIC.value)

        # Check for errors from executor
        if "error" in result:
            error_msg = result.get("error", "Unknown error")
            error_code = result.get("code", 500)
            current_app.logger.error(f"Metric execution failed: {error_msg}")
            abort(error_code, f"Failed to execute metric: {error_msg}")

        scores = result.get("scores", [])
        if len(scores) == 0:
            current_app.logger.warning(f"Metric {metric_id} returned no scores")
            abort(400, "The metric didn't return any scores")

        return jsonify({"scores": scores})

    except ValueError as e:
        abort(400, str(e))
    except Exception as e:
        current_app.logger.exception(f"Failed to execute metric {metric_id}")
        abort(500, f"Failed to execute metric: {str(e)}")
