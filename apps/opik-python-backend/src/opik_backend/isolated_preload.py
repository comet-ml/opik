"""Forkserver template preload for the isolation path.

Imported once in the template before any worker forks. The full ``opik`` package takes
~1.8s to import; this registers stub modules so that ``process_worker``'s and user
code's ``from opik.evaluation.metrics import BaseMetric`` / ``ScoreResult`` resolve to the
pure-stdlib ``_opik`` versions instead. Any other use - attribute access (``opik.Client``)
or a submodule import (``from opik.evaluation.metrics.conversation import X``) - loads the
real ``opik`` once, in the worker that needs it. Same design as the sandbox executor's
``scoring_runner.py``: Python resolves dotted submodule paths through ``sys.meta_path``,
not ``__getattr__``, so one stub class serves as both the module and the finder.

The real ``opik`` metric classes derive from the ``_opik`` ones (``ScoreResult`` is the
same object), so checks against the stub classes accept a metric written against either.
On an ``opik`` without ``_opik`` the real package is imported, as before.
"""

import importlib.util
import sys
import types
from typing import Any, Dict

_stubs: Dict[str, types.ModuleType] = {}


def _load_real_opik() -> None:
    """Evict the stubs and run the real ``opik`` init (idempotent)."""
    if not _stubs:
        return
    for name, stub in _stubs.items():
        if sys.modules.get(name) is stub:
            del sys.modules[name]
        if stub in sys.meta_path:
            sys.meta_path.remove(stub)  # type: ignore[arg-type]
    _stubs.clear()
    import opik  # noqa: F401


class _FallbackModule(types.ModuleType):
    """Stub module that loads the real ``opik`` on first use it cannot serve."""

    def __getattr__(self, name: str) -> Any:
        _load_real_opik()
        return getattr(sys.modules[self.__name__], name)

    def find_spec(self, fullname: str, path: Any, target: Any = None) -> Any:
        if not _stubs or not (fullname == "opik" or fullname.startswith("opik.")):
            return None
        _load_real_opik()
        return importlib.util.find_spec(fullname)


def _install_stubs() -> None:
    import _opik._base_metric
    import _opik._score_result

    for name in ("opik", "opik.evaluation", "opik.evaluation.metrics"):
        stub = _FallbackModule(name)
        stub.__path__ = []  # type: ignore[attr-defined]
        sys.modules[name] = stub
        _stubs[name] = stub
    # One instance serves all opik.* submodule lookups.
    sys.meta_path.insert(0, _stubs["opik"])  # type: ignore[arg-type]

    for name, module in (("base_metric", _opik._base_metric), ("score_result", _opik._score_result)):
        sys.modules[f"opik.evaluation.metrics.{name}"] = module
        _stubs[f"opik.evaluation.metrics.{name}"] = module
        setattr(_stubs["opik.evaluation.metrics"], name, module)
    _stubs["opik.evaluation.metrics"].BaseMetric = _opik._base_metric.BaseMetric  # type: ignore[attr-defined]
    _stubs["opik.evaluation.metrics"].ScoreResult = _opik._score_result.ScoreResult  # type: ignore[attr-defined]


if "opik" not in sys.modules and importlib.util.find_spec("_opik") is not None:
    _install_stubs()

# Imported with the stubs in place, so its metric imports resolve to the light layer.
from opik_backend import process_worker  # noqa: E402,F401
