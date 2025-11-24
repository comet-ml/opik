"""
Operations for the Meta-Prompt Optimizer.

This module contains extracted operations from the main optimizer:
- halloffame_ops: Hall of Fame pattern extraction and tracking
- evaluation_ops: Prompt evaluation operations
- candidate_ops: Candidate generation and sanitization
- context_ops: Task and history context building
- result_ops: Result calculation and formatting
"""

from .halloffame_ops import HallOfFameEntry, PromptHallOfFame
from . import evaluation_ops, candidate_ops, context_ops, result_ops, halloffame_ops

__all__ = [
    "HallOfFameEntry",
    "PromptHallOfFame",
    "evaluation_ops",
    "candidate_ops",
    "context_ops",
    "result_ops",
    "halloffame_ops",
]
