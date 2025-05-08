import importlib.metadata
import logging
from .logging_config import setup_logging

__version__ = importlib.metadata.version("opik_optimizer")

# Using WARNING as a sensible default to avoid flooding users with INFO/DEBUG
setup_logging(level=logging.WARNING)


# Lazy imports to avoid circular dependencies
def __getattr__(name):
    if name == "MiproOptimizer":
        from .mipro_optimizer import MiproOptimizer

        return MiproOptimizer
    elif name == "BaseOptimizer":
        from .base_optimizer import BaseOptimizer

        return BaseOptimizer
    elif name == "MetaPromptOptimizer":
        from .meta_prompt_optimizer import MetaPromptOptimizer

        return MetaPromptOptimizer
    elif name == "FewShotBayesianOptimizer":
        from .few_shot_bayesian_optimizer import FewShotBayesianOptimizer

        return FewShotBayesianOptimizer
    elif name in ["MetricConfig", "OptimizationConfig", "TaskConfig"]:
        from .optimization_config.configs import (
            MetricConfig,
            OptimizationConfig,
            TaskConfig,
        )

        return locals()[name]
    elif name in ["from_dataset_field", "from_llm_response_text"]:
        from .optimization_config.mappers import (
            from_dataset_field,
            from_llm_response_text,
        )

        return locals()[name]
    raise AttributeError(f"module 'opik_optimizer' has no attribute '{name}'")


from opik.evaluation.models.litellm import warning_filters

warning_filters.add_warning_filters()

from .optimization_result import OptimizationResult

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
    "OptimizationResult",
    "setup_logging",
]
