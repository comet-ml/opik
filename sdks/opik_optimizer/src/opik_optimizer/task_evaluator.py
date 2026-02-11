"""Backward-compatible alias for evaluation helpers."""

from .core.evaluation import _create_metric_class, evaluate, evaluate_with_result

__all__ = ["_create_metric_class", "evaluate", "evaluate_with_result"]
