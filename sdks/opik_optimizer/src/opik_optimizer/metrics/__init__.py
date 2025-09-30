"""
Metrics for Opik Optimizer evaluation.

This package provides custom metrics that extend Opik's evaluation capabilities
with specialized scoring functions for optimization tasks.
"""

from .multimodal_llm_judge import MultimodalLLMJudge

__all__ = ["MultimodalLLMJudge"]
