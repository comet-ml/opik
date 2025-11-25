"""Unit tests for HierarchicalReflectiveOptimizer."""

from opik_optimizer import HierarchicalReflectiveOptimizer


class TestHierarchicalReflectiveOptimizer:
    """Test HierarchicalReflectiveOptimizer functionality."""

    def test_optimizer_initialization(self) -> None:
        """Test that optimizer initializes with correct defaults."""
        optimizer = HierarchicalReflectiveOptimizer(
            model="openai/gpt-4o",
        )

        assert optimizer.model == "openai/gpt-4o"
        assert optimizer.n_threads == 12
        assert optimizer.max_parallel_batches == 5
        assert optimizer.batch_size == 25
        assert optimizer.verbose == 1
        assert optimizer.seed == 42

    def test_optimizer_custom_parameters(self) -> None:
        """Test optimizer with custom parameters."""
        optimizer = HierarchicalReflectiveOptimizer(
            model="openai/gpt-4o-mini",
            n_threads=8,
            max_parallel_batches=3,
            batch_size=10,
            verbose=0,
            seed=123,
        )

        assert optimizer.model == "openai/gpt-4o-mini"
        assert optimizer.n_threads == 8
        assert optimizer.max_parallel_batches == 3
        assert optimizer.batch_size == 10
        assert optimizer.verbose == 0
        assert optimizer.seed == 123

    def test_get_optimizer_metadata(self) -> None:
        """Test get_optimizer_metadata returns correct configuration."""
        optimizer = HierarchicalReflectiveOptimizer(
            model="openai/gpt-4o",
            n_threads=10,
            max_parallel_batches=4,
        )

        metadata = optimizer.get_optimizer_metadata()

        assert metadata["model"] == "openai/gpt-4o"
        assert metadata["n_threads"] == 10
        assert metadata["max_parallel_batches"] == 4
        assert metadata["seed"] == 42
        assert metadata["verbose"] == 1

    def test_counter_reset(self) -> None:
        """Test that counters reset correctly."""
        optimizer = HierarchicalReflectiveOptimizer()

        # Manually increment counters
        optimizer._increment_llm_counter()
        optimizer._increment_tool_counter()

        assert optimizer.llm_call_counter > 0
        assert optimizer.tool_call_counter > 0

        # Reset counters
        optimizer._reset_counters()

        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0
