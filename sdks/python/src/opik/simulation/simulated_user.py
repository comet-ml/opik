"""SimulatedUser class for multi-turn conversation simulation."""

from typing import Any, List, Dict, Optional
from opik.evaluation.models.models_factory import get as get_model


class SimulatedUser:
    """
    A simulated user that generates responses using LLMs or fixed responses.

    The user simulator generates string responses that are then incorporated
    into the conversation by the application logic.
    """

    def __init__(
        self,
        persona: str,
        model: str = "gpt-4o-mini",
        fixed_responses: Optional[List[str]] = None,
        max_history_messages: Optional[int] = 10,
    ):
        """
        Initialize a simulated user.

        Args:
            persona: Description of the user's personality and behavior
            model: LLM model to use for generating responses (default: gpt-4o-mini)
            fixed_responses: Optional list of predefined responses to cycle through
            max_history_messages: Maximum number of most recent conversation messages
                to include for LLM generation. Use None for no limit.
                Defaults to 10 to avoid context blowups in long runs.
        """
        if max_history_messages is not None and max_history_messages <= 0:
            raise ValueError("max_history_messages must be greater than 0 or None")

        self.persona = persona
        self.model = model
        self.fixed_responses = fixed_responses or []
        self.max_history_messages = max_history_messages
        self._response_index = 0

        # Lazy-initialize LLM backend only when needed.
        # This avoids model/provider initialization overhead for fixed-response simulations.
        self._llm: Optional[Any] = None

    def generate_response(
        self,
        conversation_history: List[Dict[str, str]],
        simulation_state: Optional[Dict[str, object]] = None,
    ) -> str:
        """
        Generate a response based on the conversation history.

        Args:
            conversation_history: List of message dicts with 'role' and 'content' keys
            simulation_state: Optional shared mutable state for the current simulation run.

        Returns:
            String response from the simulated user
        """
        # Use fixed responses first if available
        if self.fixed_responses:
            if simulation_state is not None:
                state_index_raw = simulation_state.get("user_response_index", 0)
                try:
                    if isinstance(state_index_raw, (int, float, str)):
                        state_index = int(state_index_raw)
                    else:
                        state_index = 0
                except (TypeError, ValueError):
                    state_index = 0
                response = self.fixed_responses[state_index % len(self.fixed_responses)]
                simulation_state["user_response_index"] = state_index + 1
            else:
                response = self.fixed_responses[
                    self._response_index % len(self.fixed_responses)
                ]
                self._response_index += 1
            return response

        # Generate response using LLM
        return self._generate_llm_response(conversation_history)

    def _generate_llm_response(self, conversation_history: List[Dict[str, str]]) -> str:
        """Generate response using the LLM backend."""
        if self._llm is None:
            self._llm = get_model(model_name=self.model)

        # Build system prompt with persona and clear instructions
        system_prompt = f"""You are a simulated user with the following persona: {self.persona}

Your task is to generate realistic user messages that this persona would send in a conversation.
Respond as if you are the user, not as an assistant describing the user.
Generate a single user message that fits your persona and the conversation context."""

        # Convert conversation history to messages format expected by LLM
        messages = [{"role": "system", "content": system_prompt}]

        # Add recent conversation history (optionally truncated to prevent context blowups)
        messages.extend(self._truncate_history(conversation_history))

        # Convert messages to string format for generate_string
        conversation_text = self._format_messages_as_text(messages)

        # Generate response
        try:
            response = self._llm.generate_string(input=conversation_text)
            return response
        except Exception as e:
            # Fallback response if LLM fails
            return f"I'm having trouble responding right now. ({str(e)})"

    def _format_messages_as_text(self, messages: List[Dict[str, str]]) -> str:
        """Convert message list to text format for LLM input."""
        formatted_messages = []
        for message in messages:
            role = message["role"]
            content = message["content"]
            if role == "system":
                formatted_messages.append(f"System: {content}")
            elif role == "user":
                formatted_messages.append(f"User: {content}")
            elif role == "assistant":
                formatted_messages.append(f"Assistant: {content}")
            else:
                formatted_messages.append(f"{role.title()}: {content}")

        return "\n".join(formatted_messages)

    def _truncate_history(
        self, conversation_history: List[Dict[str, str]]
    ) -> List[Dict[str, str]]:
        """Return recent history according to max_history_messages."""
        if self.max_history_messages is None:
            return conversation_history

        return conversation_history[-self.max_history_messages :]
