"""Utility helpers for opik_optimizer."""

from contextlib import contextmanager
from typing import Any
import sys

from . import tools as tools
from . import tool_helpers as tool_helpers
from . import rng as rng
from . import sampling as sampling
from .candidate import unique_ordered_by_key

_reporting_patch_depth = 0
_original_display_experiment_results: Any | None = None
_original_display_experiment_link: Any | None = None


def create_litellm_agent_class(*args: Any, **kwargs: Any) -> type[Any]:
    """Backward-compatible alias used by legacy GEPA code."""
    from ..agents.litellm_agent import LiteLLMAgent

    _ = (args, kwargs)
    return LiteLLMAgent


@contextmanager
def optimization_context(
    *,
    client: Any,
    dataset_name: str,
    objective_name: str,
    name: str | None,
    metadata: dict[str, Any] | None,
    optimization_id: str | None = None,
) -> Any:
    """
    Backward-compatible optimization context manager.
    Creates the optimization at enter and best-effort updates status on exit.
    """
    optimization = None
    try:
        optimization = client.create_optimization(
            dataset_name=dataset_name,
            objective_name=objective_name,
            metadata=metadata,
            name=name,
            optimization_id=optimization_id,
        )
    except Exception:
        optimization = None

    try:
        yield optimization
    finally:
        if optimization is not None:
            status = "completed" if sys.exc_info()[0] is None else "error"
            try:
                optimization.update(status=status)
            except Exception:
                pass


def disable_experiment_reporting() -> None:
    """Temporarily suppress Opik experiment summary/link output."""
    global _reporting_patch_depth
    global _original_display_experiment_results
    global _original_display_experiment_link

    from opik.evaluation import report

    if _reporting_patch_depth == 0:
        _original_display_experiment_results = report.display_experiment_results
        _original_display_experiment_link = report.display_experiment_link

        def _noop(*args: Any, **kwargs: Any) -> None:
            _ = (args, kwargs)
            return None

        report.display_experiment_results = _noop  # type: ignore[assignment]
        report.display_experiment_link = _noop  # type: ignore[assignment]

    _reporting_patch_depth += 1


def enable_experiment_reporting() -> None:
    """Restore Opik experiment summary/link output after suppression."""
    global _reporting_patch_depth
    global _original_display_experiment_results
    global _original_display_experiment_link

    if _reporting_patch_depth <= 0:
        return

    _reporting_patch_depth -= 1
    if _reporting_patch_depth != 0:
        return

    from opik.evaluation import report

    if _original_display_experiment_results is not None:
        report.display_experiment_results = _original_display_experiment_results  # type: ignore[assignment]
    if _original_display_experiment_link is not None:
        report.display_experiment_link = _original_display_experiment_link  # type: ignore[assignment]


# FIXME: Rewrire prompt_segments and toolcalling
__all__ = [
    "tools",
    "tool_helpers",
    "rng",
    "sampling",
    "create_litellm_agent_class",
    "optimization_context",
    "disable_experiment_reporting",
    "enable_experiment_reporting",
    "unique_ordered_by_key",
]
