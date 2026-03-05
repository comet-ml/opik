"""Tests for SimulatedUser class."""

from unittest.mock import Mock, patch
import pytest
from opik.simulation.simulated_user import SimulatedUser


class TestSimulatedUser:
    """Test cases for SimulatedUser class."""

    def test_init_with_persona_and_model(self):
        """Test SimulatedUser initialization with persona and model."""
        user = SimulatedUser(persona="You are a helpful assistant", model="gpt-4o-mini")

        assert user.persona == "You are a helpful assistant"
        assert user.model == "gpt-4o-mini"
        assert user.fixed_responses == []
        assert user.max_history_messages == 10
        assert user._response_index == 0
        assert user._llm is None

    def test_init_with_fixed_responses(self):
        """Test SimulatedUser initialization with fixed responses."""
        fixed_responses = ["Hello!", "How are you?", "Goodbye!"]
        user = SimulatedUser(persona="Test persona", fixed_responses=fixed_responses)

        assert user.fixed_responses == fixed_responses
        assert user._response_index == 0
        assert user._llm is None

    @patch("opik.simulation.simulated_user.get_model")
    def test_generate_response_with_fixed_responses__does_not_initialize_llm(
        self, mock_get_model
    ):
        fixed_responses = ["Response 1", "Response 2"]
        user = SimulatedUser(persona="Test persona", fixed_responses=fixed_responses)

        assert user.generate_response([]) == "Response 1"
        assert user.generate_response([]) == "Response 2"
        assert user._llm is None
        mock_get_model.assert_not_called()

    def test_generate_response_with_fixed_responses(self):
        """Test response generation using fixed responses."""
        fixed_responses = ["Response 1", "Response 2", "Response 3"]
        user = SimulatedUser(persona="Test persona", fixed_responses=fixed_responses)

        # First call
        response1 = user.generate_response([])
        assert response1 == "Response 1"
        assert user._response_index == 1

        # Second call
        response2 = user.generate_response([])
        assert response2 == "Response 2"
        assert user._response_index == 2

        # Third call
        response3 = user.generate_response([])
        assert response3 == "Response 3"
        assert user._response_index == 3

        # Fourth call (cycles back)
        response4 = user.generate_response([])
        assert response4 == "Response 1"
        assert user._response_index == 4

    def test_generate_response_with_fixed_responses_and_state_index(self):
        """Test fixed responses use and advance simulation_state response index."""
        user = SimulatedUser(
            persona="Test persona",
            fixed_responses=["State 1", "State 2", "State 3"],
        )
        simulation_state = {"user_response_index": 1}

        response = user.generate_response([], simulation_state=simulation_state)

        assert response == "State 2"
        assert simulation_state["user_response_index"] == 2

    def test_generate_response_with_fixed_responses_and_invalid_state_index(self):
        """Test invalid simulation_state index values fall back safely to 0."""
        user = SimulatedUser(
            persona="Test persona",
            fixed_responses=["State 1", "State 2"],
        )
        simulation_state = {"user_response_index": "next"}

        response = user.generate_response([], simulation_state=simulation_state)

        assert response == "State 1"
        assert simulation_state["user_response_index"] == 1

    @patch("opik.simulation.simulated_user.get_model")
    def test_generate_response_with_llm(self, mock_get_model):
        """Test response generation using LLM when no fixed responses."""
        # Mock the LLM response
        mock_llm_instance = Mock()
        mock_llm_instance.generate_string.return_value = "LLM generated response"
        mock_get_model.return_value = mock_llm_instance

        user = SimulatedUser(persona="You are a helpful assistant", model="gpt-4o-mini")

        conversation_history = [
            {"role": "user", "content": "Hello"},
            {"role": "assistant", "content": "Hi there!"},
        ]

        response = user.generate_response(conversation_history)

        assert response == "LLM generated response"

        # Verify LLM was called with correct input
        mock_llm_instance.generate_string.assert_called_once()
        call_args = mock_llm_instance.generate_string.call_args[1]["input"]
        assert (
            "You are a simulated user with the following persona: You are a helpful assistant"
            in call_args
        )
        assert "User: Hello" in call_args
        assert "Assistant: Hi there!" in call_args

    @patch("opik.simulation.simulated_user.get_model")
    def test_generate_response_with_llm_error(self, mock_get_model):
        """Test response generation when LLM raises an exception."""
        # Mock the LLM to raise an exception
        mock_llm_instance = Mock()
        mock_llm_instance.generate_string.side_effect = Exception("LLM error")
        mock_get_model.return_value = mock_llm_instance

        user = SimulatedUser(persona="Test persona", model="gpt-4o-mini")

        response = user.generate_response([])

        assert "I'm having trouble responding right now. (LLM error)" in response

    @patch("opik.simulation.simulated_user.get_model")
    def test_generate_response_with_long_history(self, mock_get_model):
        """Test that the default history window uses the latest 10 messages."""
        mock_llm_instance = Mock()
        mock_llm_instance.generate_string.return_value = "Response"
        mock_get_model.return_value = mock_llm_instance

        user = SimulatedUser(persona="Test persona", model="gpt-4o-mini")

        # Create a long conversation history (15 messages)
        long_history = []
        for i in range(15):
            long_history.extend(
                [
                    {"role": "user", "content": f"Message {i}"},
                    {"role": "assistant", "content": f"Response {i}"},
                ]
            )

        user.generate_response(long_history)

        # Verify LLM was called
        mock_llm_instance.generate_string.assert_called_once()
        call_args = mock_llm_instance.generate_string.call_args[1]["input"]

        # Should contain the system message and only the latest history window.
        assert (
            "You are a simulated user with the following persona: Test persona"
            in call_args
        )
        assert "User: Message 0" not in call_args
        assert "Assistant: Response 0" not in call_args
        assert "User: Message 10" in call_args
        assert "Assistant: Response 10" in call_args
        assert "User: Message 14" in call_args
        assert "Assistant: Response 14" in call_args

    @patch("opik.simulation.simulated_user.get_model")
    def test_generate_response_with_long_history_limit(self, mock_get_model):
        """Test that custom history limit truncates when configured."""
        mock_llm_instance = Mock()
        mock_llm_instance.generate_string.return_value = "Response"
        mock_get_model.return_value = mock_llm_instance

        user = SimulatedUser(
            persona="Test persona", model="gpt-4o-mini", max_history_messages=6
        )

        long_history = []
        for i in range(15):
            long_history.extend(
                [
                    {"role": "user", "content": f"Message {i}"},
                    {"role": "assistant", "content": f"Response {i}"},
                ]
            )

        user.generate_response(long_history)
        call_args = mock_llm_instance.generate_string.call_args[1]["input"]
        assert "User: Message 0" not in call_args
        assert "Assistant: Response 0" not in call_args
        assert "User: Message 11" not in call_args
        assert "Assistant: Response 11" not in call_args
        assert "User: Message 12" in call_args
        assert "Assistant: Response 12" in call_args

    def test_init_with_invalid_max_history_messages(self):
        """Test validation for max_history_messages."""
        with pytest.raises(ValueError) as exc_info:
            SimulatedUser(persona="Test persona", max_history_messages=0)

        assert "max_history_messages must be greater than 0 or None" in str(
            exc_info.value
        )
