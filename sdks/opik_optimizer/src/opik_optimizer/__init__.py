import importlib.metadata

__version__ = importlib.metadata.version("opik_optimizer")

from .mipro_optimizer import MiproOptimizer
from .base_optimizer import BaseOptimizer
from .meta_prompt_optimizer import MetaPromptOptimizer
from .few_shot_bayesian_optimizer import FewShotBayesianOptimizer
from .optimization_config.configs import MetricConfig, OptimizationConfig, TaskConfig
from .optimization_config.mappers import from_dataset_field, from_llm_response_text

from opik.evaluation.models.litellm import warning_filters
warning_filters.add_warning_filters()

__all__ = [
    "BaseOptimizer",
    "FewShotBayesianOptimizer",
    "MetaPromptOptimizer",
    "MiproOptimizer",
    "MetricConfig",
    "OptimizationConfig",
    "TaskConfig",
    "from_dataset_field",
    "from_llm_response_text",
]