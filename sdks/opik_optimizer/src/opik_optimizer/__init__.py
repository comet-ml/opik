import importlib.metadata

__version__ = importlib.metadata.version("opik_optimizer")

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
    elif name in ["MetricConfig", "OptimizationConfig", "PromptTaskConfig"]:
        from .optimization_config.configs import MetricConfig, OptimizationConfig, PromptTaskConfig
        return locals()[name]
    elif name in ["from_dataset_field", "from_llm_response_text"]:
        from .optimization_config.mappers import from_dataset_field, from_llm_response_text
        return locals()[name]
    raise AttributeError(f"module 'opik_optimizer' has no attribute '{name}'")

__all__ = [
    "BaseOptimizer",
    "FewShotBayesianOptimizer",
    "MetaPromptOptimizer",
    "MiproOptimizer",
    "MetricConfig",
    "OptimizationConfig",
    "PromptTaskConfig",
    "from_dataset_field",
    "from_llm_response_text",
]
