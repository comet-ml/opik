"""
Tests for BaseOptimizer abstract class.

This module tests the core functionality of the BaseOptimizer class,
including abstract method definitions and common functionality.
"""

from opik_optimizer.base_optimizer import BaseOptimizer


class TestBaseOptimizer:
    """Test BaseOptimizer functionality."""

    def test_base_optimizer_is_abstract(self):
        """Test that BaseOptimizer is an abstract class."""
        # BaseOptimizer is not actually abstract in the current implementation
        # but it has abstract methods that must be implemented by subclasses
        assert hasattr(BaseOptimizer, "optimize_prompt")
        assert hasattr(BaseOptimizer, "optimize_mcp")

    def test_base_optimizer_has_required_methods(self):
        """Test that BaseOptimizer has required abstract methods."""
        required_methods = ["optimize_prompt", "optimize_mcp"]

        for method_name in required_methods:
            assert hasattr(BaseOptimizer, method_name)
            method = getattr(BaseOptimizer, method_name)
            assert callable(method)

    def test_optimize_prompt_signature(self):
        """Test that optimize_prompt has the correct signature."""
        import inspect

        sig = inspect.signature(BaseOptimizer.optimize_prompt)
        params = list(sig.parameters.keys())

        expected_params = [
            "self",
            "prompt",
            "dataset",
            "metric",
            "experiment_config",
            "n_samples",
            "auto_continue",
            "agent_class",
            "kwargs",
        ]

        assert params == expected_params

    def test_optimize_mcp_signature(self):
        """Test that optimize_mcp has the correct signature."""
        import inspect

        sig = inspect.signature(BaseOptimizer.optimize_mcp)
        params = list(sig.parameters.keys())

        expected_params = [
            "self",
            "prompt",
            "dataset",
            "metric",
            "tool_name",
            "second_pass",
            "experiment_config",
            "n_samples",
            "auto_continue",
            "agent_class",
            "fallback_invoker",
            "fallback_arguments",
            "allow_tool_use_on_second_pass",
            "kwargs",
        ]

        assert params == expected_params

    def test_optimize_prompt_return_annotation(self):
        """Test that optimize_prompt has correct return annotation."""
        import inspect
        from opik_optimizer.optimization_result import OptimizationResult

        sig = inspect.signature(BaseOptimizer.optimize_prompt)
        return_annotation = sig.return_annotation

        assert return_annotation == OptimizationResult

    def test_optimize_mcp_return_annotation(self):
        """Test that optimize_mcp has correct return annotation."""
        import inspect
        from opik_optimizer.optimization_result import OptimizationResult

        sig = inspect.signature(BaseOptimizer.optimize_mcp)
        return_annotation = sig.return_annotation

        assert return_annotation == OptimizationResult
