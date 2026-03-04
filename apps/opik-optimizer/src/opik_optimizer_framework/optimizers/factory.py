from __future__ import annotations

from typing import Any


def _load_registry() -> dict[str, type]:
    from opik_optimizer_framework.optimizers.simple_optimizer import SimpleOptimizer
    from opik_optimizer_framework.optimizers.gepa.gepa_optimizer import GepaOptimizer
    from opik_optimizer_framework.optimizers.gepa_v2.gepa_optimizer import GepaV2Optimizer

    return {
        "SimpleOptimizer": SimpleOptimizer,
        "GepaOptimizer": GepaOptimizer,
        "GepaV2Optimizer": GepaV2Optimizer,
    }


def create_optimizer(name: str) -> Any:
    """Create an optimizer instance by its registered name."""
    registry = _load_registry()
    optimizer_cls = registry.get(name)
    if optimizer_cls is None:
        available = ", ".join(sorted(registry.keys()))
        raise ValueError(
            f"Unknown optimizer type: {name!r}. Available: [{available}]"
        )
    return optimizer_cls()
