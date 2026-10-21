"""Tests for run_simulation function."""

from unittest.mock import patch

import litellm

import opik
from opik.evaluation.models import models_factory
from opik.simulation.simulator import run_simulation
from opik.simulation.simulated_user import SimulatedUser
from tests.testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
    generate_project_name,
)


def _expected_app_trace(name, message, thread_id, turn, project_name):
    return TraceModel(
        id=ANY_BUT_NONE,
        name=name,
        input={"message": message, "thread_id": thread_id, "kwargs": {}},
        output={"role": "assistant", "content": f"Response to: {message}"},
        metadata={"turn": turn, "project_name": project_name},
        thread_id=thread_id,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name=name,
                input={"message": message, "thread_id": thread_id, "kwargs": {}},
                output={"role": "assistant", "content": f"Response to: {message}"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=project_name,
                spans=[],
            )
        ],
    )


def _expected_completion_trace(project_name):
    return TraceModel(
        id=ANY_BUT_NONE,
        name="fake_completion",
        input={"messages": [{"role": "user", "content": ANY_STRING}]},
        output={"choices": ANY_BUT_NONE},
        tags=["litellm"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="fake_completion",
                input={"messages": [{"role": "user", "content": ANY_STRING}]},
                output={"choices": ANY_BUT_NONE},
                tags=["litellm"],
                metadata=ANY_DICT,
                model="gpt-4o-mini",
                provider="openai",
                total_cost=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=project_name,
                spans=[],
            )
        ],
    )


class TestRunSimulation:
    """Test cases for run_simulation function."""

    def test_run_simulation_basic(self, fake_backend):
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

    def test_run_simulation_with_initial_message(self, fake_backend):
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

    def test_run_simulation_with_thread_id(self, fake_backend):
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

    def test_run_simulation_with_app_kwargs(self, fake_backend):
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

    def test_run_simulation_app_error_handling(self, fake_backend):
        """Test simulation handles app errors gracefully."""

        def failing_app(message, *, thread_id, **kwargs):
            raise Exception("App error")

        user_simulator = SimulatedUser(persona="Test user", fixed_responses=["Message"])

        result = run_simulation(
            app=failing_app, user_simulator=user_simulator, max_turns=1
        )

        history = result["conversation_history"]
        assert "Error processing message: App error" in history[1]["content"]

    def test_run_simulation_invalid_app_response(self, fake_backend):
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

    def test_run_simulation_app_returns_none(self, fake_backend):
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
    def test_run_simulation_generates_thread_id(self, mock_generate_id, fake_backend):
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

    def test_run_simulation_with_project_name(self, fake_backend):
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

    def test_run_simulation_with_project_name__traces_logged_to_that_project(
        self, fake_backend
    ):
        project_name = generate_project_name("simulation")

        def mock_app(message, *, thread_id, **kwargs):
            return {"role": "assistant", "content": f"Response to: {message}"}

        user_simulator = SimulatedUser(
            persona="Test user", fixed_responses=["Hello", "Bye"]
        )

        result = run_simulation(
            app=mock_app,
            user_simulator=user_simulator,
            project_name=project_name,
            max_turns=2,
        )
        opik.flush_tracker()

        assert len(fake_backend.trace_trees) == 2
        for turn, message in enumerate(["Hello", "Bye"], start=1):
            assert_equal(
                _expected_app_trace(
                    name="mock_app",
                    message=message,
                    thread_id=result["thread_id"],
                    turn=turn,
                    project_name=project_name,
                ),
                fake_backend.trace_trees[turn - 1],
            )

    def test_run_simulation_with_project_name__already_tracked_app__traces_logged_to_that_project(
        self, fake_backend
    ):
        project_name = generate_project_name("simulation")

        @opik.track
        def tracked_app(message, *, thread_id, **kwargs):
            return {"role": "assistant", "content": f"Response to: {message}"}

        user_simulator = SimulatedUser(persona="Test user", fixed_responses=["Hello"])

        result = run_simulation(
            app=tracked_app,
            user_simulator=user_simulator,
            project_name=project_name,
            max_turns=1,
        )
        opik.flush_tracker()

        assert len(fake_backend.trace_trees) == 1
        assert_equal(
            _expected_app_trace(
                name="tracked_app",
                message="Hello",
                thread_id=result["thread_id"],
                turn=1,
                project_name=project_name,
            ),
            fake_backend.trace_trees[0],
        )

    def test_run_simulation_with_project_name__app_tracked_with_other_project__run_simulation_project_used(
        self, fake_backend
    ):
        project_name = generate_project_name("simulation")
        app_project_name = generate_project_name("app")

        @opik.track(project_name=app_project_name)
        def tracked_app(message, *, thread_id, **kwargs):
            return {"role": "assistant", "content": f"Response to: {message}"}

        user_simulator = SimulatedUser(persona="Test user", fixed_responses=["Hello"])

        result = run_simulation(
            app=tracked_app,
            user_simulator=user_simulator,
            project_name=project_name,
            max_turns=1,
        )
        opik.flush_tracker()

        assert len(fake_backend.trace_trees) == 1
        assert_equal(
            _expected_app_trace(
                name="tracked_app",
                message="Hello",
                thread_id=result["thread_id"],
                turn=1,
                project_name=project_name,
            ),
            fake_backend.trace_trees[0],
        )

    def test_run_simulation_with_project_name__llm_simulated_user__completion_traces_logged_to_that_project(
        self, fake_backend, monkeypatch
    ):
        project_name = generate_project_name("simulation")

        # A plain function, not a MagicMock: the litellm tracker skips callables
        # that report opik_tracked, and a MagicMock reports every attribute.
        def fake_completion(model, messages, **kwargs):
            return litellm.ModelResponse(
                model=model,
                choices=[{"message": {"role": "assistant", "content": "Hi there"}}],
            )

        monkeypatch.setattr(litellm, "completion", fake_completion)
        # LiteLLMChatModel wraps litellm.completion when it is created, so drop
        # any cached instance built before the patch.
        monkeypatch.setattr(models_factory, "_MODEL_CACHE", {})

        def mock_app(message, *, thread_id, **kwargs):
            return {"role": "assistant", "content": f"Response to: {message}"}

        user_simulator = SimulatedUser(persona="Test user", model="gpt-4o-mini")

        result = run_simulation(
            app=mock_app,
            user_simulator=user_simulator,
            project_name=project_name,
            max_turns=2,
        )
        opik.flush_tracker()

        # Per turn: the simulated user's completion trace, then the app's trace
        assert len(fake_backend.trace_trees) == 4
        for turn in [1, 2]:
            assert_equal(
                _expected_completion_trace(project_name=project_name),
                fake_backend.trace_trees[2 * turn - 2],
            )
            assert_equal(
                _expected_app_trace(
                    name="mock_app",
                    message="Hi there",
                    thread_id=result["thread_id"],
                    turn=turn,
                    project_name=project_name,
                ),
                fake_backend.trace_trees[2 * turn - 1],
            )

    def test_run_simulation_max_turns_zero(self, fake_backend):
        """Test simulation with zero max_turns."""

        def mock_app(message, *, thread_id, **kwargs):
            return {"role": "assistant", "content": "Response"}

        user_simulator = SimulatedUser(persona="Test user", fixed_responses=["Message"])

        result = run_simulation(
            app=mock_app, user_simulator=user_simulator, max_turns=0
        )

        assert len(result["conversation_history"]) == 0
