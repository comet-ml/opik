from __future__ import annotations

from typing import TYPE_CHECKING, Protocol

if TYPE_CHECKING:
    from opik_optimizer_framework.evaluation_adapter import EvaluationAdapter
    from opik_optimizer_framework.event_emitter import EventEmitter
    from opik_optimizer_framework.types import (
        OptimizationContext,
        OptimizationState,
    )


class OptimizerProtocol(Protocol):
    def run(
        self,
        context: OptimizationContext,
        training_set: list[str],
        validation_set: list[str],
        evaluation_adapter: EvaluationAdapter,
        state: OptimizationState,
        event_emitter: EventEmitter,
    ) -> None: ...
