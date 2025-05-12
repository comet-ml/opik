import importlib.metadata
import logging
from .logging_config import setup_logging

__version__ = importlib.metadata.version("opik_optimizer")

# Using WARNING as a sensible default to avoid flooding users with INFO/DEBUG
setup_logging(level=logging.WARNING)

# Regular imports
from .mipro_optimizer import MiproOptimizer
from .base_optimizer import BaseOptimizer
from .meta_prompt_optimizer import MetaPromptOptimizer
from .few_shot_bayesian_optimizer import FewShotBayesianOptimizer
from .optimization_config.configs import (
    MetricConfig,
    OptimizationConfig,
    TaskConfig,
)
from .optimization_config.mappers import (
    from_dataset_field,
    from_llm_response_text,
)

from opik.evaluation.models.litellm import warning_filters

warning_filters.add_warning_filters()

from .optimization_result import OptimizationResult
from opik_optimizer.genetic_optimizer.genetic_optimizer import GeneticOptimizer

__all__ = [
    "BaseOptimizer",
    "FewShotBayesianOptimizer",
    "MetaPromptOptimizer",
    "MiproOptimizer",
    "GeneticOptimizer",
    "MetricConfig",
    "OptimizationConfig",
    "TaskConfig",
    "from_dataset_field",
    "from_llm_response_text",
    "OptimizationResult",
    "setup_logging",
]
