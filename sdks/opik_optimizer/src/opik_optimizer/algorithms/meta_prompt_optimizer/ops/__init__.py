"""
Operations for the Meta-Prompt Optimizer.

This module contains extracted operations from the main optimizer:
- halloffame_ops: Hall of Fame pattern extraction and tracking
- evaluation_ops: Prompt evaluation and selection
- candidate_ops: Candidate generation and sanitization
- history_ops: Task context + history recording helpers
- context_learning_ops: Dataset-example prompt learning helpers
- result_ops: Result calculation and formatting
"""

from ..types import HallOfFameEntry
from .halloffame_ops import PromptHallOfFame
from . import (
    candidate_ops,
    candidate_bundle_ops,
    candidate_single_ops,
    candidate_synthesis_ops,
    evaluation_ops,
    history_ops,
    context_learning_ops,
    result_ops,
    halloffame_ops,
)

__all__ = [
    "HallOfFameEntry",
    "PromptHallOfFame",
    "candidate_ops",
    "candidate_bundle_ops",
    "candidate_single_ops",
    "candidate_synthesis_ops",
    "evaluation_ops",
    "history_ops",
    "context_learning_ops",
    "result_ops",
    "halloffame_ops",
]
