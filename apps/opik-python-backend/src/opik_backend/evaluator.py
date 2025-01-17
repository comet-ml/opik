from typing import Any, Dict

from flask import request, abort, jsonify, Blueprint, current_app
from werkzeug.exceptions import HTTPException

from opik_backend.docker_runner import run_scoring_in_docker_python_container

evaluator = Blueprint('evaluator', __name__, url_prefix='/v1/private/evaluators')


@evaluator.errorhandler(400)
def bad_request(exception: HTTPException):
    return jsonify(error=str(exception)), 400


@evaluator.errorhandler(500)
def internal_server_error(exception: HTTPException):
    return jsonify(error=str(exception)), 500


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

    response = run_scoring_in_docker_python_container(code, data)
    if "error" in response:
        abort(response["code"], response["error"])

    scores = response.get("scores", [])
    if len(scores) == 0:
        current_app.logger.info("Missing ScoreResult in code '%s'", code)
        abort(400, "The provided 'code' field didn't return any 'opik.evaluation.metrics.ScoreResult'")

    return jsonify({"scores": scores})
