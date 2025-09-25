"""
Test GepaOptimizer parameter validation.
"""

import pytest
from opik_optimizer.gepa_optimizer.gepa_optimizer import GepaOptimizer


class TestGepaOptimizerValidation:
    """Test GepaOptimizer parameter validation."""

    def test_valid_parameters(self):
        """Test that valid parameters work correctly."""
        optimizer = GepaOptimizer(
            model="openai/gpt-4",
            project_name="test-project",
            reflection_model="openai/gpt-3.5-turbo",
            verbose=1,
            seed=42,
        )
        assert optimizer.model == "openai/gpt-4"
        assert optimizer.project_name == "test-project"
        assert optimizer.reflection_model == "openai/gpt-3.5-turbo"
        assert optimizer.verbose == 1
        assert optimizer.seed == 42

    def test_model_validation(self):
        """Test model parameter validation."""
        # Test None model
        with pytest.raises(
            ValueError, match="model parameter is required and cannot be None"
        ):
            GepaOptimizer(model=None)

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
            GepaOptimizer(model=123)

    def test_project_name_validation(self):
        """Test project_name parameter validation."""
        # Test valid project_name
        optimizer = GepaOptimizer(model="openai/gpt-4", project_name="test-project")
        assert optimizer.project_name == "test-project"

        # Test None project_name (should be allowed)
        optimizer = GepaOptimizer(model="openai/gpt-4", project_name=None)
        assert optimizer.project_name is None

        # Test invalid project_name type
        with pytest.raises(
            ValueError, match="project_name must be a string or None, got int"
        ):
            GepaOptimizer(model="openai/gpt-4", project_name=123)

    def test_reflection_model_validation(self):
        """Test reflection_model parameter validation."""
        # Test valid reflection_model
        optimizer = GepaOptimizer(
            model="openai/gpt-4", reflection_model="openai/gpt-3.5-turbo"
        )
        assert optimizer.reflection_model == "openai/gpt-3.5-turbo"

        # Test None reflection_model (should default to model)
        optimizer = GepaOptimizer(model="openai/gpt-4", reflection_model=None)
        assert optimizer.reflection_model == "openai/gpt-4"

        # Test invalid reflection_model type
        with pytest.raises(
            ValueError, match="reflection_model must be a string or None, got int"
        ):
            GepaOptimizer(model="openai/gpt-4", reflection_model=123)

    def test_verbose_validation(self):
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
            GepaOptimizer(model="openai/gpt-4", verbose="invalid")

        # Test negative verbose
        with pytest.raises(ValueError, match="verbose must be non-negative"):
            GepaOptimizer(model="openai/gpt-4", verbose=-1)

    def test_seed_validation(self):
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
            GepaOptimizer(model="openai/gpt-4", seed="invalid")

    def test_model_kwargs_passthrough(self):
        """Test that model_kwargs are passed through correctly."""
        optimizer = GepaOptimizer(
            model="openai/gpt-4", temperature=0.7, max_tokens=100, num_threads=8
        )
        assert optimizer.model_kwargs.get("temperature") == 0.7
        assert optimizer.model_kwargs.get("max_tokens") == 100
        assert (
            optimizer.num_threads == 8
        )  # num_threads is extracted to instance variable
