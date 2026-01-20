# mypy: disable-error-code=no-untyped-def

from typing import Any

import pytest

from opik_optimizer import EvolutionaryOptimizer


class TestEvolutionaryOptimizerInit:
    @pytest.mark.parametrize(
        "kwargs,expected",
        [
            ({"model": "gpt-4o"}, {"model": "gpt-4o", "seed": 42}),
            (
                {"model": "gpt-4o-mini", "verbose": 0, "seed": 123, "enable_moo": True},
                {"model": "gpt-4o-mini", "verbose": 0, "seed": 123, "enable_moo": True},
            ),
        ],
    )
    def test_initialization(self, kwargs: dict[str, Any], expected: dict[str, Any]) -> None:
        """Test optimizer initialization with defaults and custom params."""
        optimizer = EvolutionaryOptimizer(**kwargs)
        for key, value in expected.items():
            assert getattr(optimizer, key) == value

