import importlib.metadata
import os
import warnings
from typing import Any

from opik.evaluation.models.litellm import warning_filters

from . import datasets
from .agents.optimizable_agent import OptimizableAgent
from .api_objects.chat_prompt import ChatPrompt
from .base_optimizer import BaseOptimizer
from .core import llm_calls
from .core.results import (
    OptimizationHistoryState,
    OptimizationRound,
    OptimizationTrial,
    build_candidate_entry,
)
from .core.state import AlgorithmResult, OptimizationContext
from .core.llm_calls import build_llm_call_metadata, requested_multiple_candidates
from .utils.logging import setup_logging
from .core.results import OptimizationResult
from .metrics.multi_metric_objective import MultiMetricObjective

# FIXME: Remove once LiteLLM issue is resolved
# https://github.com/BerriAI/litellm/issues/16813
os.environ.setdefault("LITELLM_LOCAL_MODEL_COST_MAP", "True")

__version__ = importlib.metadata.version("opik_optimizer")

# Use env override when present, otherwise defaults to WARNING
setup_logging()

warning_filters.add_warning_filters()

# Silence noisy Pydantic serialization warnings emitted by upstream LiteLLM/OpenAI models.
# We rely on DOTALL regex (`(?s)`) because the warning text spans multiple lines.
# TODO(opik_optimizer/#pydantic-serialization): remove this filter once we align our expected
# response models with LiteLLM/OpenAI objects instead of ignoring the warning.
warnings.filterwarnings(
    "ignore",
    message=r"(?s)Pydantic serializer warnings:.*PydanticSerializationUnexpectedValue",
    category=UserWarning,
)

_LAZY_ALGORITHM_EXPORTS = {
    "GepaOptimizer": ("opik_optimizer.algorithms", "GepaOptimizer"),
    "MetaPromptOptimizer": ("opik_optimizer.algorithms", "MetaPromptOptimizer"),
    "EvolutionaryOptimizer": ("opik_optimizer.algorithms", "EvolutionaryOptimizer"),
    "HierarchicalReflectiveOptimizer": (
        "opik_optimizer.algorithms",
        "HierarchicalReflectiveOptimizer",
    ),
    "HRPO": ("opik_optimizer.algorithms", "HRPO"),
    "FewShotBayesianOptimizer": (
        "opik_optimizer.algorithms",
        "FewShotBayesianOptimizer",
    ),
    "ParameterOptimizer": ("opik_optimizer.algorithms", "ParameterOptimizer"),
    "ParameterSearchSpace": (
        "opik_optimizer.algorithms.parameter_optimizer",
        "ParameterSearchSpace",
    ),
    "ParameterSpec": ("opik_optimizer.algorithms.parameter_optimizer", "ParameterSpec"),
    "ParameterType": ("opik_optimizer.algorithms.parameter_optimizer", "ParameterType"),
}


def __getattr__(name: str) -> Any:
    """Lazily load heavy algorithm exports to keep base imports stable."""
    target = _LAZY_ALGORITHM_EXPORTS.get(name)
    if target is None:
        raise AttributeError(f"module {__name__!r} has no attribute {name!r}")

    module_name, attr_name = target
    module = __import__(module_name, fromlist=[attr_name])
    value = getattr(module, attr_name)
    globals()[name] = value
    return value

__all__ = [
    # Algorithms
    "FewShotBayesianOptimizer",
    "GepaOptimizer",
    "MetaPromptOptimizer",
    "EvolutionaryOptimizer",
    "HierarchicalReflectiveOptimizer",
    "HRPO",
    "ParameterOptimizer",
    "ParameterSearchSpace",
    "ParameterSpec",
    "ParameterType",
    # API Objects
    "BaseOptimizer",
    "ChatPrompt",
    "AlgorithmResult",
    "OptimizationResult",
    "OptimizationContext",
    "OptimizationHistoryState",
    "OptimizationRound",
    "OptimizationTrial",
    "OptimizableAgent",
    # Core helpers
    "llm_calls",
    "build_candidate_entry",
    "build_llm_call_metadata",
    "requested_multiple_candidates",
    # Metrics
    "MultiMetricObjective",
    # Datasets
    "datasets",
]
