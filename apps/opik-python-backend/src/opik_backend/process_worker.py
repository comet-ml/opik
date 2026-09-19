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


def get_metric_class(module: ModuleType) -> Optional[Type[BaseMetric]]:
    for _, cls in inspect.getmembers(module, inspect.isclass):
        # A BaseMetric subclass DEFINED in this module — not one merely imported
        # into it (e.g. `from opik.evaluation.metrics import Equals`), which would
        # otherwise be instantiated instead of the user's class. inspect.getmembers
        # is name-sorted, so the first match is the alphabetically-first defined
        # subclass, matching the build-time _find_basemetric_classdef selection.
        if (
            issubclass(cls, BaseMetric)
            and cls is not BaseMetric
            and cls.__module__ == module.__name__
        ):
            return cls
    return None


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


def _top_level_classdefs(tree: ast.AST) -> List[ast.ClassDef]:
    """Module-level ClassDefs only — the classes that become module attributes
    after exec, i.e. exactly what runtime ``inspect.getmembers`` sees. Excludes
    classes nested in a method or another class, which a broad ``ast.walk`` would
    otherwise pick up (and which runtime ``get_metric_class`` never instantiates).
    """
    return [node for node in getattr(tree, "body", []) if isinstance(node, ast.ClassDef)]


def _class_base_names(node: ast.ClassDef) -> set:
    """The (last-segment) names of a ClassDef's declared bases, e.g. ``BaseMetric``
    for both ``class X(BaseMetric)`` and ``class X(pkg.BaseMetric)``."""
    names = set()
    for base in node.bases:
        name = (
            base.id
            if isinstance(base, ast.Name)
            else base.attr
            if isinstance(base, ast.Attribute)
            else None
        )
        if name:
            names.add(name)
    return names


def _find_basemetric_classdef(tree: ast.AST) -> Optional[ast.ClassDef]:
    """The ClassDef that subclasses ``BaseMetric`` (directly or transitively via
    another class defined in the same file), or None.

    Static counterpart to :func:`get_metric_class` used at build time so no user
    code is executed. Matches a base written as ``BaseMetric``,
    ``something.BaseMetric``, or an ``import ... as`` alias of ``BaseMetric``.
    Indirect subclassing through an intermediate class DEFINED in the file is
    resolved transitively so the selection matches runtime ``issubclass`` (a base
    that is IMPORTED, not defined here, still can't be resolved statically — the
    caller then falls back rather than reject; see :func:`validate_user_code`).

    When a file declares more than one metric class, the class is picked
    **alphabetically by name** to match :func:`get_metric_class`, which iterates
    ``inspect.getmembers`` (name-sorted) at scoring time. Selecting the same
    class in both places keeps the statically-inferred ``score()`` signature
    flags (``accepts_var_keyword`` / ``score_params``) and metric name consistent
    with the class actually instantiated — otherwise the wrong signature/name
    could be applied and every trial would silently score 0.0 / miss its name.
    """
    aliases = _basemetric_aliases(tree)

    class_defs: dict = {}  # name -> ClassDef (last definition wins, as at runtime)
    base_names: dict = {}  # name -> set of declared base names
    for node in _top_level_classdefs(tree):
        class_defs[node.name] = node
        base_names[node.name] = _class_base_names(node)

    # Seed with classes that DIRECTLY subclass a BaseMetric alias, then
    # transitively add any class that subclasses an already-known metric class
    # defined in the file — mirroring runtime issubclass following the chain.
    metric_names = {n for n, bases in base_names.items() if bases & aliases}
    changed = True
    while changed:
        changed = False
        for name, bases in base_names.items():
            if name not in metric_names and (bases & metric_names):
                metric_names.add(name)
                changed = True

    if not metric_names:
        return None
    return class_defs[min(metric_names)]


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
        # Names this class's declared bases so we only trust a name passed to the
        # *base* constructor, not an unrelated helper's __init__.
        base_names = set()
        for base in cls.bases:
            if isinstance(base, ast.Name):
                base_names.add(base.id)
            elif isinstance(base, ast.Attribute):
                base_names.add(base.attr)

        def _is_base_constructor_call(call: ast.Call) -> bool:
            """True only for ``super().__init__(...)`` or ``<Base>.__init__(...)``
            where ``<Base>`` is one of this class's declared bases — NOT any
            incidental ``Helper.__init__(...)`` in the metric's ``__init__`` body
            (which would otherwise misname the objective)."""
            func = call.func
            if not (isinstance(func, ast.Attribute) and func.attr == "__init__"):
                return False
            value = func.value
            # super().__init__(...)
            if (
                isinstance(value, ast.Call)
                and isinstance(value.func, ast.Name)
                and value.func.id == "super"
            ):
                return True
            # <Base>.__init__(...) with <Base> a declared base of this class
            if isinstance(value, ast.Name) and value.id in base_names:
                return True
            if isinstance(value, ast.Attribute) and value.attr in base_names:
                return True
            return False

        # Common idiom: no `name` param, name passed straight to the base
        # constructor — ``super().__init__(name="my_metric")``.
        for node in ast.walk(init):
            if isinstance(node, ast.Call) and _is_base_constructor_call(node):
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


# Cap on how many wrapped causes are reported before the frames.
MAX_CAUSE_CHAIN = 5
# Cap on how many are visited to find them, so a wide exception group cannot make
# the walk itself the expensive part.
MAX_CAUSE_VISITS = 100


def user_facing_stacktrace(skip_frames: int = 1) -> str:
    """Format the current exception, cause first, with this module's own frames dropped.

    Walks frames rather than slicing a fixed number of leading lines, so the
    exception line survives however short the traceback is. A failure raised while
    binding the call arguments has no user frame at all, so a fixed slice could
    remove the message itself and report a cause of "". Returns the exception and any
    wrapped causes ahead of the frames, since the caller keeps only the first 500
    characters.
    """
    exc_type, exc, tb = sys.exc_info()
    for _ in range(skip_frames):
        if tb is None:
            break
        tb = tb.tb_next
    # Lead with the causes. The caller truncates this message to its first 500
    # characters and format_exception puts the exception last, so a failure raised a
    # few frames deep would have its cause cut off -- the same empty-cause outcome
    # this helper exists to prevent. Frames follow, and are what gets lost instead.
    # format_exception_only rather than slicing the formatted list: for a
    # SyntaxError the first entry is the offending location, not a header, so
    # dropping it by position would discard the very line the user needs. The
    # __cause__/__context__ chain is walked so a wrapped error still names its root,
    # and bounded so a long chain cannot push the frames out on its own.
    causes = []
    seen = set()
    queue = [exc]
    # Bounded on the way in, not just on the way out: a group can carry arbitrarily
    # many members, and formatting them all before discarding most is work done on
    # behalf of whatever the metric raised.
    while queue and len(causes) < MAX_CAUSE_VISITS:
        current = queue.pop(0)
        if current is None or id(current) in seen:
            continue
        seen.add(id(current))
        causes.append("".join(traceback.format_exception_only(type(current), current)).rstrip())
        # A group renders as "(N sub-exceptions)" on its own, which names nothing
        # actionable, so its members are reported alongside it.
        for member in getattr(current, "exceptions", ()) or ():
            queue.append(member)
        # `raise X from None` sets __suppress_context__, and reporting the context
        # anyway would expose what the author explicitly hid. Compared against None
        # rather than tested for truth: an exception may define __bool__/__len__ as
        # falsy, and an explicit cause must not be dropped because of it.
        if current.__cause__ is not None:
            queue.append(current.__cause__)
        elif not current.__suppress_context__:
            queue.append(current.__context__)
    # Truncated from the middle: the first entry is what was raised and the last is
    # the root, and dropping the tail would lose the root -- the one this exists to
    # surface -- on any chain deeper than the budget.
    if len(causes) > MAX_CAUSE_CHAIN:
        # Two of the budget go to the marker and the root, so the head keeps the rest.
        kept = MAX_CAUSE_CHAIN - 2
        causes = causes[:kept] + [f"... {len(causes) - kept - 1} more", causes[-1]]
    cause = "\ncaused by: ".join(causes)
    frames = "".join(traceback.format_tb(tb)).rstrip()
    return f"{cause}\n{frames}" if frames else cause


def run_user_code(code: str, data: dict, payload_type: Optional[str] = None) -> dict:
    """
    Run the scoring logic with the provided code and data.
    For trace_thread type, pass data as first positional argument.
    """
    module = ModuleType(str(uuid.uuid4()))

    try:
        exec(code, module.__dict__)
    except Exception as e:
        stacktrace = user_facing_stacktrace()
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
        stacktrace = user_facing_stacktrace()
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
        # No class statically resolves to a BaseMetric subclass. A metric whose
        # only link to BaseMetric is through an IMPORTED base (e.g.
        # `from x import MyBase; class My(MyBase)`) is not statically resolvable,
        # yet runtime get_metric_class (issubclass) instantiates it fine — so
        # hard-rejecting here would regress a previously-working metric.
        class_defs = _top_level_classdefs(tree)
        if not class_defs:
            # No class at all -> definitely not a metric -> build-time reject.
            return {
                "code": 400,
                "error": "Field 'code' in the request doesn't contain a subclass implementation of 'opik.evaluation.metrics.BaseMetric'",
            }
        # Best-effort mirror of runtime get_metric_class (name-sorted, defined-
        # only): a metric declares score(), so read the name + signature flags
        # from the alphabetically-first defined class that declares one. This
        # keeps a STRICT imported-base metric from being force-splatted (masked
        # 0.0) and preserves its name. If no defined class declares score() (it
        # is inherited from the imported base), we can't infer the signature —
        # fall back to permissive defaults and defer to runtime.
        scored = sorted(
            (c for c in class_defs if _score_funcdef(c) is not None),
            key=lambda node: node.name,
        )
        if not scored:
            return {"name": None, "accepts_var_keyword": True, "score_params": []}
        metric_class = scored[0]

    return {
        "name": _metric_name_ast(metric_class),
        "accepts_var_keyword": _score_accepts_var_keyword_ast(metric_class),
        "score_params": _score_params_ast(metric_class),
    }


def _score_funcdef_with_inheritance(tree: ast.AST, cls: ast.ClassDef):
    """``score()`` from this class, or the nearest ancestor defined in the file.

    The selected class need not declare ``score()`` itself -- ``class AMetric(ZBase)``
    with the body on ``ZBase`` is the shape runtime instantiates and calls through.
    Only in-file ancestors can be followed; an imported base leaves the signature
    unknown, which the caller treats as "fill nothing".
    """
    defined = {node.name: node for node in _top_level_classdefs(tree)}
    seen = set()
    queue = [cls]
    while queue:
        current = queue.pop(0)
        if current.name in seen:
            continue
        seen.add(current.name)
        score = _score_funcdef(current)
        if score is not None:
            return score
        for base in _class_base_names(current):
            if base in defined:
                queue.append(defined[base])
    return None


def required_score_params(code: str) -> List[str]:
    """``score()`` parameters with no default that can be passed by keyword.

    Static because this runs before any user code does, and outside the sandbox:
    the metric object is never constructed here. Returns nothing whenever the class
    this reads cannot be shown to be the one :func:`get_metric_class` will
    instantiate -- filling from a different class injects a keyword the real metric
    rejects, which is worse than not filling at all.

    That covers two ways of being unsure. No class resolves statically, so there is
    nothing to read; or one resolves but another class sorts ahead of it and could
    be a metric through an imported base, in which case runtime picks that one and
    this cannot see its signature. Runtime selection is name-sorted over runtime subclasses,
    so any earlier-sorting class that might be a metric makes the choice ambiguous.

    The receiver is dropped by position rather than by the name ``self``, which is
    only a convention: filling it would make the call pass two values for the same
    parameter. Positional-only parameters are excluded because ``score(**data)``
    cannot supply them at all.
    """
    try:
        tree = ast.parse(code)
    except Exception:
        # Anything unparseable yields no names, so the call dispatches exactly as it
        # would have. Deliberately broad: this runs in the request thread, ahead of
        # the executor, and `code` is untyped JSON.
        return []
    metric_class = _find_basemetric_classdef(tree)
    if metric_class is None:
        return []
    # A class that sorts earlier and declares score() may subclass an imported base,
    # which is invisible here but makes it the one runtime instantiates. One with no
    # bases at all cannot be a metric, so it is not a reason to give up.
    for node in _top_level_classdefs(tree):
        if (
            node.name < metric_class.name
            and _class_base_names(node)
            and _score_funcdef(node) is not None
        ):
            return []
    score = _score_funcdef_with_inheritance(tree, metric_class)
    if score is None:
        return []
    # posonlyargs precede args; the receiver is the first of the two combined.
    positional = score.args.posonlyargs + score.args.args
    fillable = positional[max(len(score.args.posonlyargs), 1):]
    defaults = score.args.defaults
    required = fillable[: len(fillable) - len(defaults)] if defaults else fillable
    names = [a.arg for a in required]
    names += [
        a.arg
        for a, default in zip(score.args.kwonlyargs, score.args.kw_defaults)
        if default is None
    ]
    return names


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
