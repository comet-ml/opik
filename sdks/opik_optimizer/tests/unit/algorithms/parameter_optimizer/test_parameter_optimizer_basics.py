# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

from typing import Any

import pytest

from opik_optimizer import ParameterOptimizer


class TestParameterOptimizerInit:
    @pytest.mark.parametrize(
        "kwargs,expected",
        [
            ({"model": "gpt-4o"}, {"model": "gpt-4o", "seed": 42}),
            (
                {"model": "gpt-4o-mini", "verbose": 0, "seed": 123},
                {"model": "gpt-4o-mini", "verbose": 0, "seed": 123},
            ),
        ],
    )
    def test_initialization(
        self, kwargs: dict[str, Any], expected: dict[str, Any]
    ) -> None:
        optimizer = ParameterOptimizer(**kwargs)
        for key, value in expected.items():
            assert getattr(optimizer, key) == value
