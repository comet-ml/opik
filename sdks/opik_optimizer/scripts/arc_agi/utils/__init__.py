"""Utility helpers for the ARC-AGI HRPO scripts."""

from .logging_utils import CONSOLE, debug_print
from .prompt_loader import load_prompts
from .visualization import print_task_preview
from .metrics import (
    DEFAULT_METRIC_SEQUENCE,
    DEFAULT_PASS_AT_K,
    LABEL_IOU_REWARD_WEIGHT,
    LIKENESS_REWARD_WEIGHT,
    FOREGROUND_REWARD_WEIGHT,
    normalized_weights,
    build_multi_metric_objective,
)
from .code_evaluator import EvaluationConfig, evaluate_arc_response
from .image_agent import ArcAgiImageAgent

__all__ = [
    "CONSOLE",
    "debug_print",
    "load_prompts",
    "print_task_preview",
    "DEFAULT_METRIC_SEQUENCE",
    "DEFAULT_PASS_AT_K",
    "LABEL_IOU_REWARD_WEIGHT",
    "LIKENESS_REWARD_WEIGHT",
    "FOREGROUND_REWARD_WEIGHT",
    "normalized_weights",
    "build_multi_metric_objective",
    "EvaluationConfig",
    "evaluate_arc_response",
    "ArcAgiImageAgent",
]
