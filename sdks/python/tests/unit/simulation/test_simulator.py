"""Tests for run_simulation function."""

from unittest.mock import patch
from opik.simulation.simulator import run_simulation
from opik.simulation.simulated_user import SimulatedUser


class TestRunSimulation:
    """Test cases for run_simulation function."""

    def test_run_simulation_basic(self):
        """Test basic simulation functionality."""

        # Mock app that returns simple responses
        def mock_app(message, *, thread_id, **kwargs):
            return {"role": "assistant", "content": f"Response to: {message}"}

        # Mock user simulator with fixed responses
        user_simulator = SimulatedUser(
            persona="Test user", fixed_responses=["Hello", "How are you?", "Goodbye"]
        )

        result = run_simulation(
            app=mock_app, user_simulator=user_simulator, max_turns=3
        )

        assert "thread_id" in result
        assert "conversation_history" in result
        assert len(result["conversation_history"]) == 6  # 3 turns * 2 messages each

        # Check conversation structure
        history = result["conversation_history"]
        assert history[0]["role"] == "user"
        assert history[0]["content"] == "Hello"
        assert history[1]["role"] == "assistant"
        assert "Response to: Hello" in history[1]["content"]

    def test_run_simulation_with_initial_message(self):
        """Test simulation with provided initial message."""

        def mock_app(message, *, thread_id, **kwargs):
            return {"role": "assistant", "content": "Got it"}

        user_simulator = SimulatedUser(
            persona="Test user", fixed_responses=["Follow up message"]
        )

        result = run_simulation(
            app=mock_app,
            user_simulator=user_simulator,
            initial_message="Custom initial message",
            max_turns=2,
        )

        history = result["conversation_history"]
        assert history[0]["content"] == "Custom initial message"
        assert history[2]["content"] == "Follow up message"

    def test_run_simulation_with_thread_id(self):
        """Test simulation with provided thread_id."""

        def mock_app(message, *, thread_id, **kwargs):
            return {"role": "assistant", "content": "Response"}

        user_simulator = SimulatedUser(persona="Test user", fixed_responses=["Message"])

        custom_thread_id = "custom-thread-123"
        result = run_simulation(
            app=mock_app,
            user_simulator=user_simulator,
            thread_id=custom_thread_id,
            max_turns=1,
        )

        assert result["thread_id"] == custom_thread_id

    def test_run_simulation_with_app_kwargs(self):
        """Test simulation with additional app kwargs."""

        def mock_app(message, *, thread_id, custom_param=None, **kwargs):
            return {"role": "assistant", "content": f"Custom: {custom_param}"}

        user_simulator = SimulatedUser(persona="Test user", fixed_responses=["Message"])

        result = run_simulation(
            app=mock_app,
            user_simulator=user_simulator,
            custom_param="test_value",
            max_turns=1,
        )

        history = result["conversation_history"]
        assert "Custom: test_value" in history[1]["content"]

    def test_run_simulation_app_error_handling(self):
        """Test simulation handles app errors gracefully."""

        def failing_app(message, *, thread_id, **kwargs):
            raise Exception("App error")

        user_simulator = SimulatedUser(persona="Test user", fixed_responses=["Message"])

        result = run_simulation(
            app=failing_app, user_simulator=user_simulator, max_turns=1
        )

        history = result["conversation_history"]
        assert "Error processing message: App error" in history[1]["content"]

    def test_run_simulation_invalid_app_response(self):
        """Test simulation handles invalid app responses."""

        def invalid_app(message, *, thread_id, **kwargs):
            return "Not a dict"  # Invalid response format

        user_simulator = SimulatedUser(persona="Test user", fixed_responses=["Message"])

        result = run_simulation(
            app=invalid_app, user_simulator=user_simulator, max_turns=1
        )

        history = result["conversation_history"]
        assert history[1]["role"] == "assistant"
        assert history[1]["content"] == "Not a dict"

    def test_run_simulation_app_returns_none(self):
        """Test simulation handles None app responses."""

        def none_app(message, *, thread_id, **kwargs):
            return None

        user_simulator = SimulatedUser(persona="Test user", fixed_responses=["Message"])

        result = run_simulation(
            app=none_app, user_simulator=user_simulator, max_turns=1
        )

        history = result["conversation_history"]
        assert history[1]["role"] == "assistant"
        assert history[1]["content"] == "No response"

    @patch("opik.simulation.simulator.id_helpers.generate_id")
    def test_run_simulation_generates_thread_id(self, mock_generate_id):
        """Test that thread_id is generated when not provided."""
        mock_generate_id.return_value = "generated-thread-456"

        def mock_app(message, *, thread_id, **kwargs):
            return {"role": "assistant", "content": "Response"}

        user_simulator = SimulatedUser(persona="Test user", fixed_responses=["Message"])

        result = run_simulation(
            app=mock_app, user_simulator=user_simulator, max_turns=1
        )

        assert result["thread_id"] == "generated-thread-456"
        mock_generate_id.assert_called_once()

    def test_run_simulation_with_project_name(self):
        """Test simulation includes project_name in result."""

        def mock_app(message, *, thread_id, **kwargs):
            return {"role": "assistant", "content": "Response"}

        user_simulator = SimulatedUser(persona="Test user", fixed_responses=["Message"])

        result = run_simulation(
            app=mock_app,
            user_simulator=user_simulator,
            project_name="test_project",
            max_turns=1,
        )

        assert result["project_name"] == "test_project"

    def test_run_simulation_max_turns_zero(self):
        """Test simulation with zero max_turns."""

        def mock_app(message, *, thread_id, **kwargs):
            return {"role": "assistant", "content": "Response"}

        user_simulator = SimulatedUser(persona="Test user", fixed_responses=["Message"])

        result = run_simulation(
            app=mock_app, user_simulator=user_simulator, max_turns=0
        )

        assert len(result["conversation_history"]) == 0
