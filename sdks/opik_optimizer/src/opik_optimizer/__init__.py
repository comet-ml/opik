import importlib.metadata
import logging

from opik.evaluation.models.litellm import warning_filters

from opik_optimizer.evolutionary_optimizer.evolutionary_optimizer import (
    EvolutionaryOptimizer,
)

from . import datasets
from .optimizable_agent import OptimizableAgent
from .optimization_config.chat_prompt import ChatPrompt
from .base_optimizer import BaseOptimizer
from .few_shot_bayesian_optimizer import FewShotBayesianOptimizer
from .gepa_optimizer import GepaOptimizer
from .logging_config import setup_logging
from .meta_prompt_optimizer import MetaPromptOptimizer
from .hierarchical_reflective_optimizer import HierarchicalReflectiveOptimizer
from .optimization_config.configs import TaskConfig
from .optimization_result import OptimizationResult
from .multi_metric_objective import MultiMetricObjective
from .parameter_optimizer import (
    ParameterOptimizer,
    ParameterSearchSpace,
    ParameterSpec,
    ParameterType,
)

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
    "TaskConfig",
    "MultiMetricObjective",
    "ParameterSearchSpace",
    "ParameterSpec",
    "ParameterType",
]
