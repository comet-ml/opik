"""Integration tests for simulation functionality."""

from unittest.mock import Mock, patch
from opik.simulation import SimulatedUser, run_simulation


class TestSimulationIntegration:
    """Integration tests for simulation functionality."""

    def test_simulation_with_class_based_app(self):
        """Test simulation with a class-based app that manages state."""

        class WeatherAgent:
            def __init__(self):
                self.histories = {}

            def __call__(self, message, *, thread_id, **kwargs):
                # Initialize history for this thread
                if thread_id not in self.histories:
                    self.histories[thread_id] = []

                # Add user message to history
                self.histories[thread_id].append(message)

                # Generate response based on full history
                response_content = f"Response to turn {len(self.histories[thread_id])}"
                assistant_message = {"role": "assistant", "content": response_content}

                # Add to history
                self.histories[thread_id].append(assistant_message)

                return assistant_message

        agent = WeatherAgent()
        user_simulator = SimulatedUser(
            persona="You are curious about weather",
            fixed_responses=["What's the weather?", "Tell me more", "Thanks!"],
        )

        result = run_simulation(app=agent, user_simulator=user_simulator, max_turns=3)

        # Verify conversation structure
        history = result["conversation_history"]
        assert len(history) == 6  # 3 turns * 2 messages

        # Verify agent maintained state
        assert len(agent.histories[result["thread_id"]]) == 6

        # Verify responses reference turn numbers
        assert "Response to turn 1" in history[1]["content"]
        assert "Response to turn 3" in history[3]["content"]
        assert "Response to turn 5" in history[5]["content"]

    def test_simulation_with_multiple_threads(self):
        """Test that different thread_ids maintain separate state."""

        class StatefulAgent:
            def __init__(self):
                self.histories = {}

            def __call__(self, message, *, thread_id, **kwargs):
                if thread_id not in self.histories:
                    self.histories[thread_id] = []

                self.histories[thread_id].append(message)
                response = {
                    "role": "assistant",
                    "content": f"Thread {thread_id} turn {len(self.histories[thread_id])}",
                }
                self.histories[thread_id].append(response)
                return response

        agent = StatefulAgent()
        user_simulator = SimulatedUser(persona="Test user", fixed_responses=["Message"])

        # Run first simulation
        result1 = run_simulation(
            app=agent, user_simulator=user_simulator, thread_id="thread-1", max_turns=2
        )

        # Run second simulation with different thread
        result2 = run_simulation(
            app=agent, user_simulator=user_simulator, thread_id="thread-2", max_turns=2
        )

        # Verify separate state
        assert result1["thread_id"] == "thread-1"
        assert result2["thread_id"] == "thread-2"
        assert len(agent.histories["thread-1"]) == 4
        assert len(agent.histories["thread-2"]) == 4

        # Verify responses reference correct threads
        assert "Thread thread-1 turn 1" in result1["conversation_history"][1]["content"]
        assert "Thread thread-2 turn 1" in result2["conversation_history"][1]["content"]

    @patch("opik.simulation.simulated_user.get_model")
    def test_simulation_with_llm_user(self, mock_get_model):
        """Test simulation with LLM-based user simulator."""
        # Mock LLM responses
        mock_llm_instance = Mock()
        mock_llm_instance.generate_string.side_effect = [
            "What's the weather like?",
            "That's interesting, tell me more",
            "Thank you for the information",
        ]
        mock_get_model.return_value = mock_llm_instance

        def mock_app(message, *, thread_id, **kwargs):
            return {"role": "assistant", "content": f"Assistant: {message}"}

        user_simulator = SimulatedUser(
            persona="You are curious about weather", model="gpt-4o-mini"
        )
        user_simulator._llm = mock_llm_instance

        result = run_simulation(
            app=mock_app, user_simulator=user_simulator, max_turns=3
        )

        # Verify LLM was called for each user message
        assert mock_llm_instance.generate_string.call_count == 3

        # Verify conversation structure
        history = result["conversation_history"]
        assert "What's the weather like?" in history[0]["content"]
        assert "Assistant: What's the weather like?" in history[1]["content"]
        assert "That's interesting, tell me more" in history[2]["content"]

    def test_simulation_error_recovery(self):
        """Test simulation continues after app errors."""
        call_count = 0

        def error_prone_app(message, *, thread_id, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 2:  # Fail on second call
                raise Exception("Temporary error")
            return {"role": "assistant", "content": f"Success {call_count}"}

        user_simulator = SimulatedUser(
            persona="Test user", fixed_responses=["Message 1", "Message 2", "Message 3"]
        )

        result = run_simulation(
            app=error_prone_app, user_simulator=user_simulator, max_turns=3
        )

        history = result["conversation_history"]

        # First turn should succeed
        assert "Success 1" in history[1]["content"]

        # Second turn should have error message
        assert "Error processing message: Temporary error" in history[3]["content"]

        # Third turn should succeed again
        assert "Success 3" in history[5]["content"]

    def test_simulation_with_complex_app_kwargs(self):
        """Test simulation with complex app configuration."""

        class ConfigurableAgent:
            def __init__(self):
                self.config = {}
                self.histories = {}

            def __call__(
                self, message, *, thread_id, model=None, temperature=None, **kwargs
            ):
                # Store configuration
                self.config[thread_id] = {
                    "model": model,
                    "temperature": temperature,
                    "other_kwargs": kwargs,
                }

                # Manage history
                if thread_id not in self.histories:
                    self.histories[thread_id] = []

                self.histories[thread_id].append(message)
                response = {"role": "assistant", "content": "Configured response"}
                self.histories[thread_id].append(response)
                return response

        agent = ConfigurableAgent()
        user_simulator = SimulatedUser(persona="Test user", fixed_responses=["Message"])

        result = run_simulation(
            app=agent,
            user_simulator=user_simulator,
            model="gpt-4",
            temperature=0.7,
            custom_param="test",
            max_turns=1,
        )

        # Verify configuration was stored
        config = agent.config[result["thread_id"]]
        assert config["model"] == "gpt-4"
        assert config["temperature"] == 0.7
        assert config["other_kwargs"]["custom_param"] == "test"
