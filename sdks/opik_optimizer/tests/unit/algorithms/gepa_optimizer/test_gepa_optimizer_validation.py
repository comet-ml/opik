"""
Test GepaOptimizer parameter validation.
"""

from opik_optimizer import GepaOptimizer


class TestGepaOptimizerValidation:
    """Test GepaOptimizer parameter validation."""

    def test_tool_optimization_not_supported_yet(self) -> None:
        """GEPA can use tools during evaluation but does not optimize tool descriptions."""
        optimizer = GepaOptimizer(model="openai/gpt-4")
        assert optimizer.supports_tool_optimization is False
