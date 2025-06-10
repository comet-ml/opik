import importlib.metadata
import logging

from opik.evaluation.models.litellm import warning_filters

from opik_optimizer.evolutionary_optimizer.evolutionary_optimizer import EvolutionaryOptimizer

from . import datasets
from .base_optimizer import BaseOptimizer
from .few_shot_bayesian_optimizer import FewShotBayesianOptimizer
from .logging_config import setup_logging
from .meta_prompt_optimizer import MetaPromptOptimizer
from .mipro_optimizer import MiproOptimizer
from .optimization_config.chat_prompt import ChatPrompt
from .optimization_config.configs import TaskConfig
from .optimization_result import OptimizationResult

__version__ = importlib.metadata.version("opik_optimizer")

# Using WARNING as a sensible default to avoid flooding users with INFO/DEBUG
setup_logging(level=logging.WARNING)

warning_filters.add_warning_filters()

__all__ = [
    "BaseOptimizer",
    "ChatPrompt",
    "FewShotBayesianOptimizer",
    "MetaPromptOptimizer",
    "MiproOptimizer",
    "EvolutionaryOptimizer",
    "OptimizationResult",
    "setup_logging",
    "datasets",
    "TaskConfig"
]
