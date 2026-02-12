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

    def test_run_simulation_with_tags(self):
        """Test simulation propagates trace tags through opik_args."""

        captured_opik_args = []

        def mock_app(message, *, thread_id, **kwargs):
            captured_opik_args.append(kwargs["opik_args"])
            return {"role": "assistant", "content": "Response"}

        # Prevent auto-decoration so we can inspect kwargs directly.
        mock_app.opik_tracked = True

        user_simulator = SimulatedUser(persona="Test user", fixed_responses=["Message"])

        result = run_simulation(
            app=mock_app,
            user_simulator=user_simulator,
            tags=["simulation", "customer_service"],
            max_turns=1,
        )

        assert result["tags"] == ["simulation", "customer_service"]
        assert captured_opik_args[0]["trace"]["tags"] == [
            "simulation",
            "customer_service",
        ]

    def test_run_simulation_max_turns_zero(self):
        """Test simulation with zero max_turns."""

        def mock_app(message, *, thread_id, **kwargs):
            return {"role": "assistant", "content": "Response"}

        user_simulator = SimulatedUser(persona="Test user", fixed_responses=["Message"])

        result = run_simulation(
            app=mock_app, user_simulator=user_simulator, max_turns=0
        )

        assert len(result["conversation_history"]) == 0

    def test_run_simulation_passes_state_to_app(self):
        """Test simulation_state is shared and mutable across app turns."""

        def stateful_app(message, *, thread_id, simulation_state, **kwargs):
            call_count = int(simulation_state.get("app_call_count", 0)) + 1
            simulation_state["app_call_count"] = call_count
            return {
                "role": "assistant",
                "content": f"call={call_count} message={message}",
            }

        user_simulator = SimulatedUser(
            persona="Test user",
            fixed_responses=["hello", "follow-up"],
        )

        result = run_simulation(
            app=stateful_app,
            user_simulator=user_simulator,
            max_turns=2,
        )

        state = result["simulation_state"]
        assert state["app_call_count"] == 2
        assert state["turn"] == 2
        assert state["last_user_message"] == "follow-up"

    def test_run_simulation_works_with_strict_app_signature(self):
        """Test compatibility with apps that do not accept simulation_state."""

        def strict_app(message, *, thread_id, opik_args):
            return {"role": "assistant", "content": f"strict:{message}"}

        user_simulator = SimulatedUser(persona="Test user", fixed_responses=["Message"])

        result = run_simulation(
            app=strict_app,
            user_simulator=user_simulator,
            max_turns=1,
        )

        history = result["conversation_history"]
        assert history[1]["content"] == "strict:Message"

    def test_run_simulation_passes_state_to_user_simulator(self):
        """Test user simulator can coordinate response indexing via simulation_state."""

        class DummyStatefulUserSimulator:
            def generate_response(self, conversation_history, simulation_state=None):
                response_index = int(simulation_state.get("dummy_idx", 0))
                simulation_state["dummy_idx"] = response_index + 1
                return f"user-{response_index}"

        def mock_app(message, *, thread_id, **kwargs):
            return {"role": "assistant", "content": f"Response to {message}"}

        result = run_simulation(
            app=mock_app,
            user_simulator=DummyStatefulUserSimulator(),
            max_turns=3,
        )

        history = result["conversation_history"]
        assert history[0]["content"] == "user-0"
        assert history[2]["content"] == "user-1"
        assert history[4]["content"] == "user-2"
        assert result["simulation_state"]["dummy_idx"] == 3

    def test_run_simulation_without_app_tracking(self):
        """Test simulations can run without per-turn app tracking."""

        def strict_app_without_opik_args(message, *, thread_id):
            return {"role": "assistant", "content": f"No tracking: {message}"}

        user_simulator = SimulatedUser(persona="Test user", fixed_responses=["Message"])

        result = run_simulation(
            app=strict_app_without_opik_args,
            user_simulator=user_simulator,
            track_app_calls=False,
            max_turns=1,
        )

        history = result["conversation_history"]
        assert history[1]["content"] == "No tracking: Message"
