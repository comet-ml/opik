import importlib.metadata

__version__ = importlib.metadata.version("opik_optimizer")

from .mipro_optimizer import MiproOptimizer
from .few_shot_optimizer import FewShotOptimizer
from .base_optimizer import BaseOptimizer
from .meta_prompt_optimizer import MetaPromptOptimizer

__all__ = [
    "BaseOptimizer",
    "MetaPromptOptimizer",
    "FewShotOptimizer",
    "MiproOptimizer",
]
