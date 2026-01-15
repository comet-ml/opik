import importlib.metadata
import logging
import os
import warnings

from opik.evaluation.models.litellm import warning_filters

from . import datasets
from .agents.optimizable_agent import OptimizableAgent
from .api_objects.chat_prompt import ChatPrompt
from .base_optimizer import AlgorithmResult, BaseOptimizer
from .algorithms import (
    GepaOptimizer,
    MetaPromptOptimizer,
    EvolutionaryOptimizer,
    HierarchicalReflectiveOptimizer,
    HRPO,
    FewShotBayesianOptimizer,
    ParameterOptimizer,
)
from .logging_config import setup_logging
from .optimization_result import OptimizationResult
from .metrics.multi_metric_objective import MultiMetricObjective
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
    "AlgorithmResult",
    "BaseOptimizer",
    "ChatPrompt",
    "FewShotBayesianOptimizer",
    "GepaOptimizer",
    "MetaPromptOptimizer",
    "EvolutionaryOptimizer",
    "HierarchicalReflectiveOptimizer",
    "HRPO",
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
