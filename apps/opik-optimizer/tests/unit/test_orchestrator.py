from unittest.mock import MagicMock

import pytest

from opik_optimizer_framework.orchestrator import run_optimization
from opik_optimizer_framework.types import OptimizationContext


class TestOrchestrator:
    def test_unknown_optimizer_raises(self):
        context = OptimizationContext(
            optimization_id="opt-1",
            dataset_name="ds",
            model="gpt-4",
            metric_type="equals",
            optimizer_type="nonexistent",
            optimizer_parameters={},
            optimizable_keys=["system_prompt"],
            baseline_config={"system_prompt": "hi", "user_message": "test", "model": "gpt-4"},
        )
        with pytest.raises(ValueError, match="Unknown optimizer type"):
            run_optimization(
                context=context,
                client=MagicMock(),
                dataset_items=[{"id": f"item-{i}"} for i in range(10)],
            )
