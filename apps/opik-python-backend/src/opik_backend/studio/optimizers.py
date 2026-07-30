"""Optimizer factory for Optimization Studio."""

import logging
from typing import Dict, Type, Any

from opik_optimizer.algorithms.gepa_optimizer.gepa_optimizer import GepaOptimizer
from opik_optimizer.algorithms.evolutionary_optimizer.evolutionary_optimizer import (
    EvolutionaryOptimizer,
)
from opik_optimizer.algorithms.hierarchical_reflective_optimizer.hierarchical_reflective_optimizer import (
    HierarchicalReflectiveOptimizer,
)

from .config import OPTIMIZER_PERFECT_SCORE, OPTIMIZER_TASK_TEMPERATURE
from .exceptions import InvalidOptimizerError
from opik_backend.utils.env_utils import get_env_int

logger = logging.getLogger(__name__)

# Default max_tokens for optimizer LLM calls to prevent truncation of structured outputs.
# Configurable via OPTSTUDIO_LLM_MAX_TOKENS environment variable.
DEFAULT_MAX_TOKENS = 8192
LLM_MAX_TOKENS = get_env_int("OPTSTUDIO_LLM_MAX_TOKENS", DEFAULT_MAX_TOKENS)


def ensure_default_model_params(
    model_params: Dict[str, Any] | None, *, deterministic: bool = False
) -> Dict[str, Any]:
    """Return model params with a reasonable max_tokens default so structured
    outputs (and baseline/per-trial task completions) don't truncate.

    Pass ``deterministic=True`` for the task model, whose completions are scored:
    it pins the temperature so the same prompt scores the same twice (see
    OPTIMIZER_TASK_TEMPERATURE). An explicit value from the run config always
    wins. Leave it False for the optimizer/reflection model, which needs sampling
    diversity to propose varied candidates.
    """
    params = dict(model_params or {})
    params.setdefault("max_tokens", LLM_MAX_TOKENS)
    if deterministic:
        params.setdefault("temperature", OPTIMIZER_TASK_TEMPERATURE)
        # Let models that fix their temperature ignore ours instead of erroring.
        params.setdefault("drop_params", True)
    return params


class OptimizerFactory:
    """Factory for creating optimizer instances.

    Maps optimizer type strings to their corresponding optimizer classes.
    Makes it easy to add new optimizers without modifying the main job processor.
    """

    _OPTIMIZERS: Dict[str, Type] = {
        "gepa": GepaOptimizer,
        "evolutionary": EvolutionaryOptimizer,
        "hierarchical_reflective": HierarchicalReflectiveOptimizer,
    }

    @classmethod
    def build(
        cls,
        optimizer_type: str,
        model: str,
        model_params: Dict[str, Any],
        optimizer_params: Dict[str, Any],
    ):
        """Build an optimizer instance from config.

        Args:
            optimizer_type: Type of optimizer (e.g., "gepa", "evolutionary", "hierarchical_reflective")
            model: LLM model identifier
            model_params: Model parameters (e.g., temperature, max_tokens)
            optimizer_params: Optimizer-specific parameters (e.g., n_iterations)

        Returns:
            Initialized optimizer instance

        Raises:
            InvalidOptimizerError: If optimizer_type is not recognized
        """
        optimizer_type = optimizer_type.lower()

        if optimizer_type not in cls._OPTIMIZERS:
            available = ", ".join(sorted(cls._OPTIMIZERS.keys()))
            raise InvalidOptimizerError(
                optimizer_type, f"Available optimizers: {available}"
            )

        # Ensure model_params has a reasonable max_tokens to prevent truncation
        # of structured outputs (JSON responses for improved prompts, analysis, etc.)
        model_params = ensure_default_model_params(model_params)

        # Studio runs treat "perfect" as full marks (OPIK-7511) — the SDK's
        # 0.95 default ends strong-baseline runs with zero candidates. Every
        # optimizer accepts perfect_score in its constructor; an explicit value
        # in the run's optimizer_params still wins.
        optimizer_params = dict(optimizer_params)
        optimizer_params.setdefault("perfect_score", OPTIMIZER_PERFECT_SCORE)

        logger.debug(
            f"Initializing {optimizer_type} optimizer with params: {optimizer_params}"
        )

        optimizer_class = cls._OPTIMIZERS[optimizer_type]
        try:
            optimizer = optimizer_class(
                model=model, model_parameters=model_params, **optimizer_params
            )
        except (TypeError, ValueError) as exc:
            raise InvalidOptimizerError(
                optimizer_type,
                f"Constructor rejected the provided parameters: {exc}",
            ) from exc

        logger.debug(f"Created {optimizer_type} optimizer instance")
        return optimizer

    @classmethod
    def list_available(cls) -> list:
        """List all available optimizer types.

        Returns:
            List of optimizer type strings
        """
        return sorted(cls._OPTIMIZERS.keys())
