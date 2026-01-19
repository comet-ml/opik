import importlib.metadata
import os
import warnings

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
from .algorithms import (
    GepaOptimizer,
    MetaPromptOptimizer,
    EvolutionaryOptimizer,
    HierarchicalReflectiveOptimizer,
    HRPO,
    FewShotBayesianOptimizer,
    ParameterOptimizer,
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

__all__ = [
    # Algorithms
    "FewShotBayesianOptimizer",
    "GepaOptimizer",
    "MetaPromptOptimizer",
    "EvolutionaryOptimizer",
    "HierarchicalReflectiveOptimizer",
    "HRPO",
    "ParameterOptimizer",
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
