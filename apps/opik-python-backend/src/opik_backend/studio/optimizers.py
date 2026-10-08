"""Optimizer factory for Optimization Studio."""

import logging
import math
import re
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

# Google asks to keep Gemini 3 at its default temperature of 1.0: lower values
# "may lead to looping or degraded performance"
# (https://ai.google.dev/gemini-api/docs/gemini-3). drop_params cannot catch it:
# the gateway's openai/ prefix hides the provider from litellm, so the pin would
# reach Google. An allow-list of the generations known to take a low temperature,
# the same one the frontend's supportsGeminiSamplingParams uses, so a newer
# generation keeps its default until someone checks it.
_LOW_TEMPERATURE_GEMINI_GENERATIONS = re.compile(
    r"^gemini-(?:1\.0|1\.5|2\.0|2\.5)(?:-|$)"
)
_LOW_TEMPERATURE_UNVERSIONED_GEMINI_IDS = frozenset({"gemini-pro-vision"})


def keeps_default_temperature(model: str | None) -> bool:
    model_id = (model or "").strip().rsplit("/", 1)[-1]
    if not model_id.startswith("gemini-"):
        return False
    return not (
        _LOW_TEMPERATURE_GEMINI_GENERATIONS.match(model_id)
        or model_id in _LOW_TEMPERATURE_UNVERSIONED_GEMINI_IDS
    )


def ensure_default_model_params(
    model_params: Dict[str, Any] | None,
    *,
    deterministic: bool = False,
    model: str | None = None,
) -> Dict[str, Any]:
    """Return model params with a reasonable max_tokens default so structured
    outputs (and baseline/per-trial task completions) don't truncate.

    Pass ``deterministic=True`` for the task model, whose completions are scored:
    it pins the temperature so repeated evaluations of one prompt agree (see
    OPTIMIZER_TASK_TEMPERATURE). An explicit temperature from the run config still
    wins, but a ``null`` one does not — the studio config can carry explicit nulls,
    and ``setdefault`` would forward that ``None`` to litellm. Models that fix
    their own temperature (the gpt-5 family) must ignore the pin rather than fail
    the run; that is already guaranteed process-wide by ``litellm.drop_params =
    True`` in ``opik_optimizer/base_optimizer.py``, which the runner imports, so
    this does not set ``drop_params`` per call. Gemini 3 and newer never get the
    pin (see ``keeps_default_temperature``), so pass the task ``model``.
    Leave ``deterministic`` False for the optimizer/reflection model, which needs
    sampling diversity to propose varied candidates.
    """
    params = dict(model_params or {})
    if params.get("max_tokens") is None:
        params["max_tokens"] = LLM_MAX_TOKENS
    if (
        deterministic
        and params.get("temperature") is None
        and not keeps_default_temperature(model)
    ):
        params["temperature"] = OPTIMIZER_TASK_TEMPERATURE
    return params


def _resolve_perfect_score(value: Any, optimizer_type: str) -> float:
    """Validate the run's ``perfect_score`` override, falling back to the default.

    The value comes from the studio config, so it can be absent, explicitly
    ``null``, or junk. It ends up in ``baseline_score >= perfect_score`` deep in a
    run, where ``None`` or a NaN raises/mis-compares long after the config that
    caused it — so reject it here, where the error can still name the field.
    ``0`` is a legal value (it disables threshold stopping), so this must not
    treat it as falsy.
    """
    if value is None:
        return OPTIMIZER_PERFECT_SCORE
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise InvalidOptimizerError(
            optimizer_type,
            f"perfect_score must be a finite number, got {type(value).__name__}",
        )
    if not math.isfinite(value):
        raise InvalidOptimizerError(
            optimizer_type, f"perfect_score must be a finite number, got {value}"
        )
    return float(value)


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
        optimizer_params["perfect_score"] = _resolve_perfect_score(
            optimizer_params.get("perfect_score", None), optimizer_type
        )

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
