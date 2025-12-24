import importlib.metadata
import logging
import os

from opik.evaluation.models.litellm import warning_filters

from . import datasets
from .agents.optimizable_agent import OptimizableAgent
from .api_objects.chat_prompt import ChatPrompt
from .base_optimizer import BaseOptimizer
from .algorithms import (
    GepaOptimizer,
    MetaPromptOptimizer,
    EvolutionaryOptimizer,
    HierarchicalReflectiveOptimizer,
    FewShotBayesianOptimizer,
    ParameterOptimizer,
)
from .logging_config import setup_logging
from .optimization_result import OptimizationResult
from .multi_metric_objective import MultiMetricObjective
from .algorithms.parameter_optimizer import (
    ParameterSearchSpace,
    ParameterSpec,
    ParameterType,
)

# FIXME: Remove once LiteLLM issue is resolved
# https://github.com/BerriAI/litellm/issues/16813
os.environ.setdefault("LITELLM_LOCAL_MODEL_COST_MAP", "True")

__version__ = importlib.metadata.version("opik_optimizer")

# Using WARNING as a sensible default to avoid flooding users with INFO/DEBUG
setup_logging(level=logging.WARNING)

warning_filters.add_warning_filters()

__all__ = [
    "BaseOptimizer",
    "ChatPrompt",
    "FewShotBayesianOptimizer",
    "GepaOptimizer",
    "MetaPromptOptimizer",
    "EvolutionaryOptimizer",
    "HierarchicalReflectiveOptimizer",
    "ParameterOptimizer",
    "OptimizationResult",
    "OptimizableAgent",
    "setup_logging",
    "datasets",
    "MultiMetricObjective",
    "ParameterSearchSpace",
    "ParameterSpec",
    "ParameterType",
]
