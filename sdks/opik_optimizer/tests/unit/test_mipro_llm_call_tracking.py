from unittest.mock import Mock
from opik_optimizer.mipro_optimizer import MiproOptimizer


class TestMiproLlmCallTracking:
    """Test LLM call tracking in MIPRO optimizer."""

    def test_llm_calls_tracked_from_dspy_program(self) -> None:
        """Test that LLM calls are properly tracked from DSPy program module."""
        # Create a mock optimizer
        optimizer = MiproOptimizer(
            model="openai/gpt-4o",
            project_name="test_project",
        )

        # Mock the DSPy program module with total_calls attribute
        mock_program = Mock()
        mock_program.total_calls = 5  # DSPy tracked 5 LLM calls
        mock_program.dump_state.return_value = {
            "signature": {"instructions": "Test prompt"},
            "demos": [],
        }

        # Mock the best_programs structure
        optimizer.best_programs = [{"score": 0.8, "program": mock_program}]

        # Mock the metric
        optimizer.opik_metric = Mock()
        optimizer.opik_metric.__name__ = "test_metric"

        # Call get_best method
        result = optimizer.get_best()

        # Verify that LLM calls are tracked from DSPy program
        assert result.llm_calls == 5, f"Expected 5 LLM calls, got {result.llm_calls}"

    def test_llm_calls_uses_higher_counter(self) -> None:
        """Test that the higher of our counter or DSPy's counter is used."""
        # Create a mock optimizer
        optimizer = MiproOptimizer(
            model="openai/gpt-4o",
            project_name="test_project",
        )

        # Set our counter to a higher value
        optimizer.llm_call_counter = 10

        # Mock the DSPy program module with lower total_calls
        mock_program = Mock()
        mock_program.total_calls = 5  # DSPy tracked 5 LLM calls
        mock_program.dump_state.return_value = {
            "signature": {"instructions": "Test prompt"},
            "demos": [],
        }

        # Mock the best_programs structure
        optimizer.best_programs = [{"score": 0.8, "program": mock_program}]

        # Mock the metric
        optimizer.opik_metric = Mock()
        optimizer.opik_metric.__name__ = "test_metric"

        # Call get_best method
        result = optimizer.get_best()

        # Verify that the higher counter is used
        assert result.llm_calls == 10, f"Expected 10 LLM calls, got {result.llm_calls}"

    def test_llm_calls_fallback_when_no_dspy_calls(self) -> None:
        """Test that our counter is used when DSPy program has no total_calls."""
        # Create a mock optimizer
        optimizer = MiproOptimizer(
            model="openai/gpt-4o",
            project_name="test_project",
        )

        # Set our counter
        optimizer.llm_call_counter = 3

        # Mock the DSPy program module without total_calls attribute
        mock_program = Mock()
        # Explicitly remove total_calls attribute to ensure getattr returns 0
        del mock_program.total_calls
        mock_program.dump_state.return_value = {
            "signature": {"instructions": "Test prompt"},
            "demos": [],
        }

        # Mock the best_programs structure
        optimizer.best_programs = [{"score": 0.8, "program": mock_program}]

        # Mock the metric
        optimizer.opik_metric = Mock()
        optimizer.opik_metric.__name__ = "test_metric"

        # Call get_best method
        result = optimizer.get_best()

        # Verify that our counter is used as fallback
        assert result.llm_calls == 3, f"Expected 3 LLM calls, got {result.llm_calls}"

    def test_llm_calls_error_case_with_optimizer_total_calls(self) -> None:
        """Test LLM call tracking in error case when optimizer has total_calls."""
        # Create a mock optimizer
        optimizer = MiproOptimizer(
            model="openai/gpt-4o",
            project_name="test_project",
        )

        # Set our counter
        optimizer.llm_call_counter = 2

        # Mock the optimizer with total_calls
        mock_optimizer = Mock()
        mock_optimizer.total_calls = 7
        optimizer.optimizer = mock_optimizer

        # Mock the metric
        optimizer.opik_metric = Mock()
        optimizer.opik_metric.name = "test_metric"

        # Call get_best method when no best_programs exist (error case)
        optimizer.best_programs = []
        result = optimizer.get_best()

        # Verify that the higher counter is used
        assert result.llm_calls == 7, f"Expected 7 LLM calls, got {result.llm_calls}"
