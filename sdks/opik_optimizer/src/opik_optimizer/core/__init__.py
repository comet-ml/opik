"""Core optimization runtime primitives."""

from .evaluation import evaluate, evaluate_with_result
from .llm_calls import build_llm_call_metadata, requested_multiple_candidates
from .results import (
    OptimizationHistoryState,
    OptimizationResult,
    OptimizationRound,
    OptimizationTrial,
    build_candidate_entry,
)
from .state import AlgorithmResult, FinishReason, OptimizationContext

__all__ = [
    "AlgorithmResult",
    "FinishReason",
    "OptimizationContext",
    "OptimizationHistoryState",
    "OptimizationResult",
    "OptimizationRound",
    "OptimizationTrial",
    "build_candidate_entry",
    "build_llm_call_metadata",
    "evaluate",
    "evaluate_with_result",
    "requested_multiple_candidates",
]
