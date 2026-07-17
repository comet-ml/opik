import importlib.metadata
import os

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
from .algorithms import (
    GepaOptimizer,
    MetaPromptOptimizer,
    EvolutionaryOptimizer,
    HierarchicalReflectiveOptimizer,
    HRPO,
    FewShotBayesianOptimizer,
    ParameterOptimizer,
)
from .algorithms.parameter_optimizer import (
    ParameterSearchSpace,
    ParameterSpec,
    ParameterType,
)
from .utils.logging import setup_logging
from .core.results import OptimizationResult
from .metrics.multi_metric_objective import MultiMetricObjective

# FIXME: Remove once LiteLLM issue is resolved
# https://github.com/BerriAI/litellm/issues/16813
os.environ.setdefault("LITELLM_LOCAL_MODEL_COST_MAP", "True")

__version__ = importlib.metadata.version("opik_optimizer")

# Use env override when present, otherwise defaults to WARNING
setup_logging()

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
