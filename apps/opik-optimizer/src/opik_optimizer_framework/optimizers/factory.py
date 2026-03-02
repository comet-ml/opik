from __future__ import annotations

from typing import Any


class OptimizerFactory:
    """Registry and factory for optimizer implementations."""

    _registry: dict[str, type] = {}

    @classmethod
    def register(cls, name: str, optimizer_class: type) -> None:
        cls._registry[name] = optimizer_class

    @classmethod
    def create(cls, name: str) -> Any:
        optimizer_cls = cls._registry.get(name)
        if optimizer_cls is None:
            available = ", ".join(sorted(cls._registry.keys()))
            raise ValueError(
                f"Unknown optimizer type: {name!r}. "
                f"Available: [{available}]"
            )
        return optimizer_cls()

    @classmethod
    def available(cls) -> list[str]:
        return sorted(cls._registry.keys())


def _register_builtins() -> None:
    from opik_optimizer_framework.optimizers.simple_optimizer import SimpleOptimizer
    from opik_optimizer_framework.optimizers.gepa.gepa_optimizer import GepaOptimizer
    OptimizerFactory.register("SimpleOptimizer", SimpleOptimizer)
    OptimizerFactory.register("GepaOptimizer", GepaOptimizer)


_register_builtins()
