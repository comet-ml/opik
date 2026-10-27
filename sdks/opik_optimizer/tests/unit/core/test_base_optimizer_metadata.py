"""Unit tests for BaseOptimizer metadata/config helpers."""

# mypy: disable-error-code=no-untyped-def

from __future__ import annotations


from typing import Any

import pytest

from opik_optimizer.core import agent as agent_utils
from tests.unit.fixtures.base_optimizer_test_helpers import ConcreteOptimizer


@pytest.mark.parametrize(
    "reasoning_parameters",
    [None, {}, {"temperature": 0.2, "metadata": {"owner": "reasoning"}}],
)
def test_reasoning_settings_respect_empty_overrides_and_copy_inputs(
    reasoning_parameters,
):
    import copy

    optimizer_parameters: dict[str, Any] = {
        "temperature": 0.9,
        "metadata": {"owner": "optimizer"},
    }
    expected_optimizer = copy.deepcopy(optimizer_parameters)
    expected_reasoning = copy.deepcopy(
        optimizer_parameters if reasoning_parameters is None else reasoning_parameters
    )
    optimizer = ConcreteOptimizer(
        model="optimizer-model",
        model_parameters=optimizer_parameters,
        reasoning_model="reasoning-model",
        reasoning_model_parameters=reasoning_parameters,
    )
    optimizer_parameters["metadata"]["owner"] = "changed"
    if reasoning_parameters:
        reasoning_parameters["metadata"]["owner"] = "changed"
    assert optimizer.model_parameters == expected_optimizer
    assert optimizer.reasoning_model_parameters == expected_reasoning
    optimizer.model_parameters["metadata"]["owner"] = "changed again"
    assert optimizer.reasoning_model_parameters == expected_reasoning


def test_agent_config_does_not_substitute_the_optimizer_model(simple_chat_prompt):
    optimizer = ConcreteOptimizer(model="optimizer-only-model")
    simple_chat_prompt.model = None
    config = agent_utils.build_agent_config(
        optimizer=optimizer, prompt=simple_chat_prompt
    )
    assert "model" not in config


def test_model_parameters_accept_non_copyable_values():
    import threading

    class Client:
        def __init__(self) -> None:
            self._lock = threading.RLock()

    client = Client()
    optimizer = ConcreteOptimizer(
        model="optimizer-model", model_parameters={"client": client}
    )
    assert optimizer.model_parameters["client"] is client
    assert optimizer.reasoning_model_parameters["client"] is client
    assert optimizer.model_parameters is not optimizer.reasoning_model_parameters
