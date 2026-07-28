"""Tests for OptimizerFactory in Optimization Studio.

Covers W13: constructor-arg errors must surface as InvalidOptimizerError, not
just unknown-type errors.
"""

import pytest
from unittest.mock import MagicMock, patch

from opik_backend.studio.optimizers import OptimizerFactory
from opik_backend.studio.exceptions import InvalidOptimizerError


class TestOptimizerFactoryUnknownType:
    """Existing guard: unknown optimizer type raises InvalidOptimizerError."""

    def test_unknown_type_raises_invalid_optimizer_error(self):
        with pytest.raises(InvalidOptimizerError) as exc_info:
            OptimizerFactory.build(
                optimizer_type="nonexistent_optimizer",
                model="openai/gpt-4o",
                model_params={},
                optimizer_params={},
            )
        assert "nonexistent_optimizer" in str(exc_info.value)
        assert "Available optimizers" in str(exc_info.value)

    def test_unknown_type_error_has_optimizer_type_attribute(self):
        with pytest.raises(InvalidOptimizerError) as exc_info:
            OptimizerFactory.build(
                optimizer_type="bad_type",
                model="openai/gpt-4o",
                model_params={},
                optimizer_params={},
            )
        assert exc_info.value.optimizer_type == "bad_type"


class TestOptimizerFactoryBadParamsRaisesTypedError:
    """W13: constructor param errors must surface as InvalidOptimizerError."""

    def test_bad_kwarg_raises_invalid_optimizer_error(self):
        """An unrecognised kwarg to the optimizer constructor must raise
        InvalidOptimizerError, not a raw TypeError."""
        with patch.dict(
            OptimizerFactory._OPTIMIZERS,
            {"_test_bad": _make_bad_constructor(TypeError("unexpected keyword argument 'nonexistent'"))},
        ):
            with pytest.raises(InvalidOptimizerError) as exc_info:
                OptimizerFactory.build(
                    optimizer_type="_test_bad",
                    model="openai/gpt-4o",
                    model_params={},
                    optimizer_params={"nonexistent": True},
                )
        assert "_test_bad" in str(exc_info.value)
        # The reason string must surface, not a raw TypeError
        assert "Constructor" in str(exc_info.value) or "parameters" in str(exc_info.value).lower()

    def test_value_error_in_constructor_raises_invalid_optimizer_error(self):
        """A ValueError in the optimizer constructor must raise
        InvalidOptimizerError, not propagate raw."""
        with patch.dict(
            OptimizerFactory._OPTIMIZERS,
            {"_test_valuerr": _make_bad_constructor(ValueError("n_iterations must be > 0"))},
        ):
            with pytest.raises(InvalidOptimizerError) as exc_info:
                OptimizerFactory.build(
                    optimizer_type="_test_valuerr",
                    model="openai/gpt-4o",
                    model_params={},
                    optimizer_params={"n_iterations": -1},
                )
        assert "_test_valuerr" in str(exc_info.value)

    def test_invalid_optimizer_error_has_optimizer_type_attribute(self):
        with patch.dict(
            OptimizerFactory._OPTIMIZERS,
            {"_test_attr": _make_bad_constructor(TypeError("bad param"))},
        ):
            with pytest.raises(InvalidOptimizerError) as exc_info:
                OptimizerFactory.build(
                    optimizer_type="_test_attr",
                    model="openai/gpt-4o",
                    model_params={},
                    optimizer_params={},
                )
        assert exc_info.value.optimizer_type == "_test_attr"

    def test_non_type_value_error_propagates_as_original(self):
        """Errors that are NOT TypeError/ValueError (e.g. RuntimeError) must
        propagate unchanged — we only catch construction-arg errors."""
        with patch.dict(
            OptimizerFactory._OPTIMIZERS,
            {"_test_runtime": _make_bad_constructor(RuntimeError("disk full"))},
        ):
            with pytest.raises(RuntimeError):
                OptimizerFactory.build(
                    optimizer_type="_test_runtime",
                    model="openai/gpt-4o",
                    model_params={},
                    optimizer_params={},
                )


class TestOptimizerFactoryListAvailable:
    def test_list_available_returns_known_types(self):
        available = OptimizerFactory.list_available()
        assert "gepa" in available
        assert "evolutionary" in available
        assert "hierarchical_reflective" in available
        assert sorted(available) == available  # must be sorted


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_bad_constructor(exc: Exception):
    """Return a fake optimizer class whose __init__ raises ``exc``."""
    class _BadOptimizer:
        def __init__(self, *args, **kwargs):
            raise exc
    return _BadOptimizer
