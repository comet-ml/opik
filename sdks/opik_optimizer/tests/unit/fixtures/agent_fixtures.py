"""Pytest fixtures for mock agent construction."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import pytest


@pytest.fixture
def mock_agent() -> Callable[..., MagicMock]:
    """
    Factory for creating mock OptimizableAgent-like objects.

    The returned mock exposes an `invoke_agent` method used by optimizers.
    """

    def _create(
        return_value: str = "Mock LLM response",
        *,
        side_effect: Callable[..., Any] | None = None,
    ) -> MagicMock:
        mock = MagicMock()

        def invoke_impl(prompts: Any, dataset_item: Any, **kwargs: Any) -> Any:
            if side_effect is not None:
                return side_effect(prompts, dataset_item, **kwargs)
            return return_value

        mock.invoke_agent = MagicMock(side_effect=invoke_impl)
        return mock

    return _create
