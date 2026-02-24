"""Backward-compatible benchmark registry import path.

This module preserves the historical ``benchmarks.core.benchmark_config`` path
while the registry implementation lives under ``benchmarks.packages.registry``.
"""

from benchmarks.packages.registry import (
    DATASET_CONFIG,
    INITIAL_PROMPTS,
    MODELS,
    OPTIMIZER_CONFIGS,
    BenchmarkDatasetConfig,
    BenchmarkExperimentConfig,
    BenchmarkOptimizerConfig,
    BenchmarkProjectConfig,
    create_answer_relevance_metric,
    create_context_precision,
    create_context_recall,
    create_levenshtein_ratio_metric,
    equals,
    hallucination,
    list_packages,
    resolve_package,
)

__all__ = [
    "BenchmarkDatasetConfig",
    "BenchmarkProjectConfig",
    "BenchmarkOptimizerConfig",
    "BenchmarkExperimentConfig",
    "create_levenshtein_ratio_metric",
    "equals",
    "create_answer_relevance_metric",
    "create_context_precision",
    "create_context_recall",
    "hallucination",
    "DATASET_CONFIG",
    "OPTIMIZER_CONFIGS",
    "MODELS",
    "INITIAL_PROMPTS",
    "resolve_package",
    "list_packages",
]
