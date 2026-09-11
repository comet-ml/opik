"""Lightweight entrypoint for core Opik metric types.

Provides :class:`BaseMetric` and :class:`ScoreResult` without pulling in
the full ``opik`` package, keeping import time near zero.

Usage::

    from _opik import BaseMetric, ScoreResult
"""

from ._score_result import ScoreResult
from ._base_metric import BaseMetric

__all__ = ["BaseMetric", "ScoreResult"]
