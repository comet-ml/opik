import ast
import signal
import sys
import traceback
import inspect
import uuid
import logging
from types import ModuleType
from typing import Type, Union, List, Optional

from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
from .payload_types import PayloadType

# Set up logging for the worker
logger = logging.getLogger("process_worker")
handler = logging.StreamHandler(sys.stderr)
formatter = logging.Formatter(
    "[%(asctime)s] Worker PID %(process)d: [%(levelname)s] %(message)s"
)
handler.setFormatter(formatter)
if not logger.hasHandlers():
    logger.addHandler(handler)
logger.setLevel(logging.INFO)


def get_metric_class(module: ModuleType) -> Type[BaseMetric]:
    for _, cls in inspect.getmembers(module, inspect.isclass):
        # Find user-defined subclasses of BaseMetric (not BaseMetric itself)
        if issubclass(cls, BaseMetric) and cls is not BaseMetric:
            return cls


def _basemetric_aliases(tree: ast.AST) -> set:
    """Local names that refer to ``BaseMetric``, resolved from import statements.

    Always includes the literal ``"BaseMetric"``; additionally picks up the
    ``from opik.evaluation.metrics import BaseMetric as BM`` alias case so a base
    written as ``BM`` is still recognized.
    """
    aliases = {"BaseMetric"}
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom):
            for alias in node.names:
                if alias.name == "BaseMetric" and alias.asname:
                    aliases.add(alias.asname)
    return aliases


def _find_basemetric_classdef(tree: ast.AST) -> Optional[ast.ClassDef]:
    """First ClassDef that directly subclasses ``BaseMetric`` (by name), or None.

    Static counterpart to :func:`get_metric_class` used at build time so no user
    code is executed. Matches a base written as ``BaseMetric``,
    ``something.BaseMetric``, or an ``import ... as`` alias of ``BaseMetric``.
    Indirect subclassing (via an intermediate user base) is not resolvable
    statically and is treated as "no subclass" — a rare case for these
    single-file metrics.
    """
    aliases = _basemetric_aliases(tree)
    for node in ast.walk(tree):
        if isinstance(node, ast.ClassDef):
            for base in node.bases:
                name = (
                    base.id
                    if isinstance(base, ast.Name)
                    else base.attr
                    if isinstance(base, ast.Attribute)
                    else None
                )
                if name in aliases:
                    return node
    return None


def _score_funcdef(cls: ast.ClassDef):
    for node in cls.body:
        if (
            isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
            and node.name == "score"
        ):
            return node
    return None


def _score_accepts_var_keyword_ast(cls: ast.ClassDef) -> bool:
    """Whether ``score()`` declares ``**kwargs`` (VAR_KEYWORD), read statically.

    A strict signature such as ``score(self, output, reference)`` must NOT
    receive extra dataset columns as unexpected keywords; a ``**kwargs``
    signature absorbs the remaining columns (studio/metrics.py data assembly).
    Permissive default (``True``) when no ``score()`` is found so we never starve
    a metric of columns — preserving the historical full-splat behavior.
    """
    score = _score_funcdef(cls)
    if score is None:
        return True
    return score.args.kwarg is not None


def _score_params_ast(cls: ast.ClassDef) -> List[str]:
    """Declared ``score()`` parameter names (excluding ``self``), read statically.

    Used to restrict the kwargs passed to a strict (no-``**kwargs``) signature so
    only its declared params are supplied — never extra dataset columns.
    """
    score = _score_funcdef(cls)
    if score is None:
        return []
    args = score.args
    names = [
        p.arg
        for p in (list(args.posonlyargs) + list(args.args) + list(args.kwonlyargs))
    ]
    return [n for n in names if n != "self"]


def _metric_name_ast(cls: ast.ClassDef) -> Optional[str]:
    """Best-effort static metric name, tried in order: the string literal passed
    to a ``super().__init__(name="...")`` (or ``BaseMetric.__init__(..., name=)``)
    call — the most common idiom; else the string default of ``__init__``'s
    ``name`` param; else a class-level ``name = "..."``; else None (caller falls
    back to "code"). Dynamic/computed names aren't resolvable statically."""
    init = next(
        (
            n
            for n in cls.body
            if isinstance(n, ast.FunctionDef) and n.name == "__init__"
        ),
        None,
    )
    if init is not None:
        # Common idiom: no `name` param, name passed straight to the base
        # constructor — ``super().__init__(name="my_metric")``.
        for node in ast.walk(init):
            if (
                isinstance(node, ast.Call)
                and isinstance(node.func, ast.Attribute)
                and node.func.attr == "__init__"
            ):
                for kw in node.keywords:
                    if (
                        kw.arg == "name"
                        and isinstance(kw.value, ast.Constant)
                        and isinstance(kw.value.value, str)
                    ):
                        return kw.value.value
        args = init.args
        positional = list(args.posonlyargs) + list(args.args)
        defaults = list(args.defaults)
        if defaults:
            for p, d in zip(positional[-len(defaults) :], defaults):
                if (
                    p.arg == "name"
                    and isinstance(d, ast.Constant)
                    and isinstance(d.value, str)
                ):
                    return d.value
        for p, d in zip(args.kwonlyargs, args.kw_defaults):
            if (
                p.arg == "name"
                and isinstance(d, ast.Constant)
                and isinstance(d.value, str)
            ):
                return d.value
    for node in cls.body:
        if isinstance(node, ast.Assign) and any(
            isinstance(t, ast.Name) and t.id == "name" for t in node.targets
        ):
            if isinstance(node.value, ast.Constant) and isinstance(
                node.value.value, str
            ):
                return node.value.value
    return None


def to_scores(score_result: Union[ScoreResult, List[ScoreResult]]) -> List[ScoreResult]:
    scores = []
    if isinstance(score_result, ScoreResult):
        scores = [score_result]
    elif isinstance(score_result, list):
        for item in score_result:
            if isinstance(item, ScoreResult):
                scores.append(item)
    return scores


def run_user_code(code: str, data: dict, payload_type: Optional[str] = None) -> dict:
    """
    Run the scoring logic with the provided code and data.
    For trace_thread type, pass data as first positional argument.
    """
    module = ModuleType(str(uuid.uuid4()))

    try:
        exec(code, module.__dict__)
    except Exception as e:
        stacktrace = "\n".join(traceback.format_exc().splitlines()[3:])
        return {
            "code": 400,
            "error": f"Field 'code' contains invalid Python code: {stacktrace}",
        }

    metric_class = get_metric_class(module)
    if metric_class is None:
        return {
            "code": 400,
            "error": "Field 'code' in the request doesn't contain a subclass implementation of 'opik.evaluation.metrics.BaseMetric'",
        }

    score_result: Union[ScoreResult, List[ScoreResult]] = []
    try:
        metric = metric_class()

        # Handle trace_thread type differently - pass data as first positional argument
        if payload_type == PayloadType.TRACE_THREAD.value:
            score_result = metric.score(data)
        else:
            # Regular scoring - unpack data as keyword arguments
            score_result = metric.score(**data)
    except Exception as e:
        stacktrace = "\n".join(traceback.format_exc().splitlines()[3:])
        return {
            "code": 400,
            "error": f"The provided 'code' and 'data' fields can't be evaluated: {stacktrace}",
        }

    scores = to_scores(score_result)

    return {"scores": [score.__dict__ for score in scores]}


def validate_user_code(code: str) -> dict:
    """Statically validate a metric's ``code`` via ``ast`` — WITHOUT executing it.

    Build-time counterpart to :func:`run_user_code`. It only *parses* the code
    (``ast.parse``, never ``exec``) to (a) catch syntax errors, (b) confirm a
    ``BaseMetric`` subclass is present, and (c) extract the metric ``name`` plus
    the ``score()`` parameter shape. **No untrusted user code runs at build
    time** — neither module-level statements nor ``__init__`` — closing the
    earlier gap where those ran in-process outside the sandbox. The only
    execution of user code remains ``score()`` at scoring time, which still
    honors ``PYTHON_CODE_EXECUTOR_STRATEGY`` (docker/process) via
    ``studio/metrics.py::_run_code_metric``.

    Not running ``score()`` also fixes the original false-rejection (OPIK-7172):
    the previous probe ran ``score(output="")`` with no dataset columns, so any
    metric requiring a dataset-derived keyword was wrongly rejected at build.

    Error taxonomy mirrors the sandbox executor's ``scoring_runner.py``:

    - invalid Python (syntax error)
    - no ``BaseMetric`` subclass present

    Returns ``{"name", "accepts_var_keyword", "score_params"}`` on success
    (``name`` may be ``None`` when it isn't a static string literal — the caller
    falls back to "code"; ``score_params`` lists ``score()``'s declared
    parameters excluding ``self``, used to restrict a strict signature's kwargs),
    or ``{"code": 400, "error": <message>}`` on failure. Indirect subclassing
    and dynamically-computed names aren't resolvable statically.
    """
    try:
        tree = ast.parse(code)
    except SyntaxError as exc:
        return {
            "code": 400,
            "error": f"Field 'code' contains invalid Python code: {exc}",
        }

    metric_class = _find_basemetric_classdef(tree)
    if metric_class is None:
        return {
            "code": 400,
            "error": "Field 'code' in the request doesn't contain a subclass implementation of 'opik.evaluation.metrics.BaseMetric'",
        }

    return {
        "name": _metric_name_ast(metric_class),
        "accepts_var_keyword": _score_accepts_var_keyword_ast(metric_class),
        "score_params": _score_params_ast(metric_class),
    }


def worker_process_main(connection):
    # Workers should ignore SIGINT; parent ProcessExecutor will manage shutdown.
    signal.signal(signal.SIGINT, signal.SIG_IGN)

    try:
        # Signal READY to the parent process via the pipe
        connection.send("READY")

        # Keep reading commands until told to exit or pipe closes
        while True:
            try:
                # Read a command from the pipe - this will block until we get a command
                command_data = connection.recv()
                if command_data is None or command_data.get("command") == "EXIT":
                    break

                # Parse the command (code, data, and payload_type)
                code = command_data.get("code", "")
                input_data = command_data.get("data", {})
                payload_type = command_data.get("payload_type")

                # Execute the code
                result = run_user_code(code, input_data, payload_type)

                # Return the result via the pipe
                connection.send(result)
            except EOFError as e:
                # This occurs when the parent closes the pipe, e.g., during shutdown.
                # The worker will break from its loop and be terminated by SIGTERM from the parent.
                logger.info(
                    f"Received EOF error, probably parent closed the pipe: {str(e)}"
                )
                break
            except Exception as e:
                # Report any errors via the pipe
                error_msg = {
                    "code": 500,
                    "error": f"Worker error: {str(e)}",
                    "traceback": traceback.format_exc(),
                }
                try:
                    connection.send(error_msg)
                except Exception as send_e:
                    logger.error(f"Failed to send error to parent: {send_e}")
                logger.error(f"Encountered error: {e}")
                logger.error(traceback.format_exc())

    except Exception as e:
        # If we get an error during initialization, log it and exit
        # Also try to send error over pipe if possible
        error_payload = {
            "code": 500,
            "error": f"Worker initialization error: {str(e)}",
            "traceback": traceback.format_exc(),
        }
        try:
            connection.send(error_payload)
        except Exception:
            pass  # Pipe might not be usable
        logger.error(f"Worker initialization error: {str(e)}\n{traceback.format_exc()}")
        sys.exit(1)
