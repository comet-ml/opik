"""Unit tests for BaseOptimizer cleanup behaviors."""

from __future__ import annotations

from typing import Any, cast
from unittest.mock import MagicMock

from tests.unit.fixtures.base_optimizer_test_helpers import ConcreteOptimizer


class TestCleanup:
    """Tests for cleanup method."""

    def test_cleanup_resets_counters(self) -> None:
        """cleanup should reset call counters."""
        optimizer = ConcreteOptimizer(model="gpt-4")
        optimizer._increment_llm_counter()
        optimizer._increment_llm_call_tools_counter()

        optimizer.cleanup()

        assert optimizer.llm_call_counter == 0
        assert optimizer.llm_call_tools_counter == 0

    def test_cleanup_clears_opik_client(self) -> None:
        """cleanup should clear the Opik client reference."""
        optimizer = ConcreteOptimizer(model="gpt-4")
        optimizer._opik_client = cast(Any, MagicMock())

        optimizer.cleanup()

        assert optimizer._opik_client is None

