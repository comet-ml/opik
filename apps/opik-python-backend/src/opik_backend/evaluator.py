import os
from typing import Any, Dict

from flask import request, abort, jsonify, Blueprint, current_app
from werkzeug.exceptions import HTTPException

from opik_backend.executor import CodeExecutorBase
from opik_backend.http_utils import build_error_response
from opik_backend.payload_types import PayloadType
from opik_backend.process_worker import required_score_params
from opik_backend.score_validation import has_usable_score

# Built-ins the scorer injects rather than resolving from a trace/span path.
RESERVED_BUILT_INS = frozenset({"spans"})

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
    if not isinstance(code, str):
        # Checked here rather than left to the executor: the two strategies disagree
        # on a non-string. ProcessExecutor reaches exec() and comes back 400, while
        # DockerExecutor sizes the payload before its try block and raises
        # AttributeError, which surfaces as a 500 the Java caller then retries.
        abort(400, "Field 'code' must be a string")

    data: Dict[Any, Any] = payload.get("data")
    if data is None:
        abort(400, "Field 'data' is missing in the request")

    # Extract type information for conversation thread metrics
    payload_type = payload.get("type")

    # An online-scoring rule maps each score() parameter to a trace/span field, and
    # a field the entity never logged resolves to nothing and arrives with its key
    # absent -- which misses an argument the signature requires and scores nothing.
    # Passing None says the entity had no value there, which is the outcome the rule
    # wants. Deliberately applied here and not in the shared scoring code: the
    # optimization studio runs the same code with a mapped *dataset column* absent
    # and requires the opposite -- score(**data) must raise, so the item is reported
    # as an explained 0.0 rather than a silent score (OPIK-7172). Same shape, two
    # contracts, and only the caller separates them.
    #
    # Known gap: a parameter the rule never mapped arrives the same way and is filled
    # too, where it should fail as a configuration error. Telling the two apart needs
    # the rule's declared argument names from the caller (OPIK-8526).
    #
    # `spans` is excluded because it is not path-resolved: the scorer injects it only
    # when the rule declares it, so its absence always means the rule never asked for
    # it -- a configuration error that should keep failing by name, not be filled.
    if isinstance(data, dict) and payload_type != PayloadType.TRACE_THREAD.value:
        for name in required_score_params(code):
            if name not in RESERVED_BUILT_INS:
                data.setdefault(name, None)

    # Get the executor from app context and run the code
    response = get_executor().run_scoring(code, data, payload_type)

    if "error" in response:
        abort(response["code"], response["error"])

    scores = response.get("scores", [])
    if len(scores) == 0:
        current_app.logger.info("Missing ScoreResult in code '%s'", code)
        abort(400, "The provided 'code' field didn't return any 'opik.evaluation.metrics.ScoreResult'")

    # A mixed list is passed through on purpose: the usable scores still reach the backend, which drops
    # the rest and names them on the rule's log stream. Only a wholly unusable response is rejected,
    # which is the same class of user error as returning no ScoreResult at all, just above.
    if not has_usable_score(scores):
        current_app.logger.info("No usable ScoreResult in code '%s'", code)
        abort(400, "The provided 'code' field didn't return any usable "
                   "'opik.evaluation.metrics.ScoreResult'")

    return jsonify({"scores": scores})
