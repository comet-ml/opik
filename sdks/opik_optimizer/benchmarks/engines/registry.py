from __future__ import annotations

from benchmarks.engines.base import BenchmarkEngine
from benchmarks.engines.local.engine import LocalEngine
from benchmarks.engines.modal.engine import ModalEngine

_ENGINES: dict[str, BenchmarkEngine] = {
    "local": LocalEngine(),
    "modal": ModalEngine(),
}


def get_engine(name: str) -> BenchmarkEngine:
    try:
        return _ENGINES[name]
    except KeyError as exc:
        raise ValueError(
            f"Unknown engine '{name}'. Available engines: {', '.join(sorted(_ENGINES))}"
        ) from exc


def list_engines() -> list[str]:
    return sorted(_ENGINES.keys())
