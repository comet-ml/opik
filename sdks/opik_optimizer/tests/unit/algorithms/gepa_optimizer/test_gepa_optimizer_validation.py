"""
Test GepaOptimizer parameter validation.
"""

import pytest
from opik_optimizer import GepaOptimizer


class TestGepaOptimizerValidation:
    """Test GepaOptimizer parameter validation."""

    def test_valid_parameters(self) -> None:
        """Test that valid parameters work correctly."""
        optimizer = GepaOptimizer(
            model="openai/gpt-4",
            n_threads=6,
            verbose=1,
            seed=42,
        )
        assert optimizer.model == "openai/gpt-4"
        assert optimizer.n_threads == 6
        assert optimizer.verbose == 1
        assert optimizer.seed == 42

    def test_model_validation(self) -> None:
        """Test model parameter validation."""
        # Test None model
        with pytest.raises(
            ValueError, match="model parameter is required and cannot be None"
        ):
            GepaOptimizer(model=None)  # type: ignore[arg-type]

        # Test empty string model
        with pytest.raises(
            ValueError, match="model cannot be empty or whitespace-only"
        ):
            GepaOptimizer(model="")

        # Test whitespace-only model
        with pytest.raises(
            ValueError, match="model cannot be empty or whitespace-only"
        ):
            GepaOptimizer(model="   ")

        # Test non-string model
        with pytest.raises(ValueError, match="model must be a string, got int"):
            GepaOptimizer(model=123)  # type: ignore[arg-type]

    def test_n_threads_validation(self) -> None:
        """Test n_threads parameter validation."""
        # Test valid n_threads
        optimizer = GepaOptimizer(model="openai/gpt-4", n_threads=10)
        assert optimizer.n_threads == 10

        # Test default n_threads
        optimizer = GepaOptimizer(model="openai/gpt-4")
        assert optimizer.n_threads == 12  # Default value

    def test_verbose_validation(self) -> None:
        """Test verbose parameter validation."""
        # Test valid verbose values
        optimizer = GepaOptimizer(model="openai/gpt-4", verbose=0)
        assert optimizer.verbose == 0

        optimizer = GepaOptimizer(model="openai/gpt-4", verbose=1)
        assert optimizer.verbose == 1

        optimizer = GepaOptimizer(model="openai/gpt-4", verbose=2)
        assert optimizer.verbose == 2

        # Test invalid verbose type
        with pytest.raises(ValueError, match="verbose must be an integer, got str"):
            GepaOptimizer(model="openai/gpt-4", verbose="invalid")  # type: ignore[arg-type]

        # Test negative verbose
        with pytest.raises(ValueError, match="verbose must be non-negative"):
            GepaOptimizer(model="openai/gpt-4", verbose=-1)

    def test_seed_validation(self) -> None:
        """Test seed parameter validation."""
        # Test valid seed values
        optimizer = GepaOptimizer(model="openai/gpt-4", seed=42)
        assert optimizer.seed == 42

        optimizer = GepaOptimizer(model="openai/gpt-4", seed=0)
        assert optimizer.seed == 0

        optimizer = GepaOptimizer(model="openai/gpt-4", seed=-1)
        assert optimizer.seed == -1  # Negative seeds are allowed

        # Test invalid seed type
        with pytest.raises(ValueError, match="seed must be an integer, got str"):
            GepaOptimizer(model="openai/gpt-4", seed="invalid")  # type: ignore[arg-type]

    def test_model_kwargs_passthrough(self) -> None:
        """Test that model_kwargs are passed through correctly."""
        optimizer = GepaOptimizer(
            model="openai/gpt-4",
            model_parameters={"temperature": 0.7, "max_tokens": 100},
            n_threads=8,
        )
        assert optimizer.model_parameters.get("temperature") == 0.7
        assert optimizer.model_parameters.get("max_tokens") == 100
        assert optimizer.n_threads == 8

    def test_tool_optimization_not_supported_yet(self) -> None:
        """GEPA can use tools during evaluation but does not optimize tool descriptions."""
        optimizer = GepaOptimizer(model="openai/gpt-4")
        assert optimizer.supports_tool_optimization is False
