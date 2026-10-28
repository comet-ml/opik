import dataclasses
import datetime
import decimal
import email.utils
import importlib.util
import inspect
import json
import sys
import traceback
import types
import uuid
from sys import argv
from typing import Type, Union, List, Any

# ---------------------------------------------------------------------------
# Lightweight import patching
# ---------------------------------------------------------------------------
# The full `opik` package takes ~2.5s to import (pydantic_settings, REST
# client, sentry, logfire, 40+ metric classes).  Under CPU contention in the
# sandbox pods this alone can exhaust the 9s execution timeout.
#
# The `_opik` package provides pure-stdlib BaseMetric and ScoreResult (~8ms).
# We register stub modules in sys.modules so that user code doing
#   `from opik.evaluation.metrics import BaseMetric`
# resolves to the lightweight versions without triggering the real `opik`
# init.  Any other usage — attribute access (`opik.Client`) or submodule
# imports (`from opik.evaluation.metrics.conversation import X`) — falls
# back to loading the real `opik` package.  Python's import machinery
# resolves dotted submodule paths via the parent's `__path__` and
# `sys.meta_path` finders, not via `__getattr__`, so a single stub class
# plays both roles: a `ModuleType` for attribute access, and a
# `find_spec` finder on `sys.meta_path` for submodule resolution.  Both
# routes funnel through `_load_real_opik`, which evicts the stubs from
# `sys.modules` and `sys.meta_path` and runs `import opik` exactly once.
# ---------------------------------------------------------------------------

import _opik._base_metric
import _opik._score_result

_stubs: dict = {}


def _load_real_opik() -> None:
    """Evict all stubs and trigger the real `opik` package init (idempotent)."""
    if not _stubs:
        return
    for name, stub in _stubs.items():
        if sys.modules.get(name) is stub:
            del sys.modules[name]
        if stub in sys.meta_path:
            sys.meta_path.remove(stub)
    _stubs.clear()
    import opik  # noqa: F401,F811 — triggers the real init


class _FallbackModule(types.ModuleType):
    """Stub module that lazy-loads the real `opik` package on first use.

    Handles both fallback routes with one class:

    - ``__getattr__`` — covers attribute access on the stub
      (``from opik import Client``).
    - ``find_spec`` — covers submodule imports that the installed stubs
      don't satisfy (``from opik.evaluation.metrics.conversation import X``).
      Python consults ``sys.meta_path`` for dotted-path resolution, not
      the parent module's ``__getattr__``, so the stub registers itself
      as a meta-path finder as well.

    Both routes delegate to ``_load_real_opik``, after which the stub is
    out of ``sys.modules`` and ``sys.meta_path`` and the standard import
    machinery takes over with the real ``opik`` package.
    """

    def __getattr__(self, name: str) -> Any:
        _load_real_opik()
        return getattr(sys.modules[self.__name__], name)

    def find_spec(self, fullname: str, path, target=None):
        if not _stubs or not (fullname == "opik" or fullname.startswith("opik.")):
            return None
        _load_real_opik()
        return importlib.util.find_spec(fullname)


for _name in ["opik", "opik.evaluation", "opik.evaluation.metrics"]:
    _stub = _FallbackModule(_name)
    _stub.__path__ = []  # type: ignore[attr-defined]
    sys.modules[_name] = _stub
    _stubs[_name] = _stub

# One instance serves all `opik.*` submodule lookups — any stub will do.
sys.meta_path.insert(0, _stubs["opik"])

sys.modules["opik.evaluation.metrics.base_metric"] = _opik._base_metric
sys.modules["opik.evaluation.metrics.score_result"] = _opik._score_result
_stubs["opik.evaluation.metrics.base_metric"] = _opik._base_metric
_stubs["opik.evaluation.metrics.score_result"] = _opik._score_result

sys.modules["opik.evaluation.metrics"].base_metric = _opik._base_metric  # type: ignore[attr-defined]
sys.modules["opik.evaluation.metrics"].score_result = _opik._score_result  # type: ignore[attr-defined]
sys.modules["opik.evaluation.metrics"].BaseMetric = _opik._base_metric.BaseMetric  # type: ignore[attr-defined]
sys.modules["opik.evaluation.metrics"].ScoreResult = _opik._score_result.ScoreResult  # type: ignore[attr-defined]

# Now these resolve to the lightweight versions
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

# Constants
TRACE_THREAD_METRIC_TYPE = "trace_thread"  # Referenced in the payload_types.py as it's not available in the scoring_commands.py process

def get_metric_class(module: types.ModuleType) -> Type[BaseMetric]:
    for _, cls in inspect.getmembers(module, inspect.isclass):
        if issubclass(cls, BaseMetric) and cls != BaseMetric:
            return cls
    return None


def to_scores(score_result: Union[ScoreResult, List[ScoreResult]]) -> List[ScoreResult]:
    scores = []
    if score_result is None:
        return scores
    if isinstance(score_result, ScoreResult):
        scores = [score_result]
    elif isinstance(score_result, list):
        for item in score_result:
            if isinstance(item, ScoreResult):
                scores.append(item)
    return scores


def user_facing_stacktrace(skip_frames: int = 1) -> str:
    """Format the current exception with this code's own frames dropped.

    Walks frames rather than slicing a fixed number of leading lines, so the
    exception line survives however short the traceback is. A failure raised while
    binding the call arguments has no user frame at all, and how many lines pad the
    traceback depends on how this is packaged, so a fixed slice could remove the
    message itself and report a cause of "".
    """
    exc_type, exc, tb = sys.exc_info()
    for _ in range(skip_frames):
        if tb is None:
            break
        tb = tb.tb_next
    # The exception leads because the caller truncates this message to its first 500
    # characters, so frames are what gets lost on a deep traceback, not the cause.
    # format_exception_only rather than slicing the formatted list: for a SyntaxError
    # the first entry is the offending location, not a header.
    cause = "".join(traceback.format_exception_only(exc_type, exc)).rstrip()
    frames = "".join(traceback.format_tb(tb)).rstrip()
    return f"{cause}\n{frames}" if frames else cause

class _Unencodable(TypeError):
    """A value json can't encode; carries only the value's type name."""


def _json_value(value: Any) -> Any:
    """A JSON value for what json can't encode itself, as the process executor's worker encodes it.

    The worker uses Flask's DefaultJSONProvider, which this image doesn't ship, so its rules are repeated here with
    the stdlib: dates as RFC 822 dates in GMT (a naive datetime taken as UTC, a date as its midnight), Decimal and UUID
    as strings, dataclasses as dicts, objects with __html__ as that string; then array-likes via tolist().
    """
    if isinstance(value, datetime.date):
        if not isinstance(value, datetime.datetime):
            value = datetime.datetime.combine(value, datetime.time(), tzinfo=datetime.timezone.utc)
        elif value.tzinfo is None:
            value = value.replace(tzinfo=datetime.timezone.utc)
        else:
            value = value.astimezone(datetime.timezone.utc)
        return email.utils.format_datetime(value, usegmt=True)
    if isinstance(value, (decimal.Decimal, uuid.UUID)):
        return str(value)
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        return dataclasses.asdict(value)
    if hasattr(value, "__html__"):
        return str(value.__html__())
    tolist = getattr(value, "tolist", None)
    if callable(tolist):
        return tolist()
    raise _Unencodable(type(value).__name__)


def _unencodable_error(scores: List[ScoreResult]) -> str:
    """Names the field, or the metadata key, that can't be encoded, and the type there; never the value."""
    for score in scores:
        for field, value in score.__dict__.items():
            items = value.items() if field == "metadata" and isinstance(value, dict) else [(None, value)]
            for key, item in items:
                try:
                    json.dumps(item, default=_json_value)
                except _Unencodable as e:
                    where = f"metadata key {key!r}" if key is not None else f"field {field!r}"
                    return (f"The metric's ScoreResult {where} holds a value of type '{e}', "
                            f"which can't be returned as JSON")
                except (TypeError, ValueError):
                    where = f"metadata key {key!r}" if key is not None else f"field {field!r}"
                    return f"The metric's ScoreResult {where} can't be returned as JSON"
    return "The metric's ScoreResult can't be returned as JSON"


code = argv[1]
data = json.loads(argv[2])
payload_type = argv[3] if len(argv) > 3 else None

module = types.ModuleType(str(uuid.uuid4()))

try:
    exec(code, module.__dict__)
except Exception:  
    stacktrace = user_facing_stacktrace()  
    print(json.dumps({"error": f"Field 'code' contains invalid Python code: {stacktrace}"}))
    exit(1)

metric_class = get_metric_class(module)
if metric_class is None:
    print(json.dumps({"error": "Field 'code' in the request doesn't contain a subclass implementation of 'opik.evaluation.metrics.BaseMetric'"}))
    exit(1)

score_result : Union[ScoreResult, List[ScoreResult]] = []
try:
    metric = metric_class()
    
    # Handle trace_thread type differently - pass data as first positional argument
    if payload_type == TRACE_THREAD_METRIC_TYPE:
        score_result = metric.score(data)
    else:
        # Regular scoring - unpack data as keyword arguments
        score_result = metric.score(**data)
except Exception:
    stacktrace = user_facing_stacktrace()
    print(json.dumps({"error": f"The provided 'code' and 'data' fields can't be evaluated: {stacktrace}"}))
    exit(1)
        
scores = to_scores(score_result)

try:
    response = json.dumps({"scores": [score.__dict__ for score in scores]}, default=_json_value)
except (TypeError, ValueError):
    print(json.dumps({"error": _unencodable_error(scores)}))
    exit(1)
print(response)
