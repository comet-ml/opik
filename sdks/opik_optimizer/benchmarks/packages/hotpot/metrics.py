from __future__ import annotations

from collections.abc import Callable

from benchmarks.metrics.hotpot import hotpot_exact_match, hotpot_f1


def default_hotpot_metrics() -> list[Callable]:
    return [hotpot_exact_match, hotpot_f1]
