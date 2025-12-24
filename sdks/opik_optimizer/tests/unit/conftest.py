"""
Centralized test fixtures for opik_optimizer unit tests.

This module provides factory fixtures for common mocking patterns:
- LLM call mocking (sync and async)
- Opik platform mocking (client, datasets)
- Rate limiting bypass
- Common test data (prompts, datasets, metrics)

Usage:
    These fixtures are automatically available to all tests in the unit/ directory
    and its subdirectories via pytest's conftest.py discovery mechanism.
"""

import pytest
from unittest.mock import MagicMock
from typing import Any

from opik_optimizer import ChatPrompt


# ============================================================
# LLM Call Mocks (Most Commonly Used)
# ============================================================


@pytest.fixture
def mock_llm_call(monkeypatch: pytest.MonkeyPatch):
    """
    Factory fixture for mocking synchronous LLM calls.

    This is the most commonly used mock in the test suite. It intercepts
    calls to `opik_optimizer._llm_calls.call_model()` and returns the
    configured response.

    Usage:
        def test_something(mock_llm_call):
            # Return a simple string
            mock_llm_call("Generated response")

            # Return a structured Pydantic model
            mock_llm_call(MyPydanticModel(field="value"))

            # Raise an error
            mock_llm_call(raises=ValueError("API error"))

            # Use a side_effect function for dynamic responses
            mock_llm_call(side_effect=lambda **kw: f"Response for {kw['model']}")

    Returns:
        A factory function that configures the mock and returns the fake function.
    """

    def _configure(
        response: Any = None,
        *,
        raises: Exception | None = None,
        side_effect: Any | None = None,
    ):
        captured_calls: list[dict[str, Any]] = []

        def fake_call_model(**kwargs):
            captured_calls.append(kwargs)
            if raises:
                raise raises
            if side_effect:
                return side_effect(**kwargs)
            return response

        monkeypatch.setattr("opik_optimizer._llm_calls.call_model", fake_call_model)
        fake_call_model.calls = captured_calls  # type: ignore[attr-defined]
        return fake_call_model

    return _configure


@pytest.fixture
def mock_llm_call_async(monkeypatch: pytest.MonkeyPatch):
    """
    Factory fixture for mocking asynchronous LLM calls.

    Similar to mock_llm_call but for async contexts. Intercepts calls to
    `opik_optimizer._llm_calls.call_model_async()`.

    Usage:
        async def test_something(mock_llm_call_async):
            mock_llm_call_async("Generated response")
            # Your async code that calls call_model_async
    """

    def _configure(
        response: Any = None,
        *,
        raises: Exception | None = None,
        side_effect: Any | None = None,
    ):
        captured_calls: list[dict[str, Any]] = []

        async def fake_call_model_async(**kwargs):
            captured_calls.append(kwargs)
            if raises:
                raise raises
            if side_effect:
                if callable(side_effect):
                    result = side_effect(**kwargs)
                    # Handle both sync and async side effects
                    if hasattr(result, "__await__"):
                        return await result
                    return result
                return side_effect
            return response

        monkeypatch.setattr(
            "opik_optimizer._llm_calls.call_model_async", fake_call_model_async
        )
        fake_call_model_async.calls = captured_calls  # type: ignore[attr-defined]
        return fake_call_model_async

    return _configure


@pytest.fixture
def mock_llm_sequence(monkeypatch: pytest.MonkeyPatch):
    """
    Mock LLM to return different responses on successive calls.

    Useful for testing retry logic, multi-step conversations, or any scenario
    where the LLM should return different values each time it's called.

    Usage:
        def test_retry_logic(mock_llm_sequence):
            # Returns "first" on call 1, "second" on call 2, etc.
            counter = mock_llm_sequence(["first", "second", "third"])

            # Can also include exceptions in the sequence
            counter = mock_llm_sequence([
                ValueError("Temporary error"),
                "Success after retry"
            ])

            # Access call count via counter["n"]
            assert counter["n"] == 2
    """

    def _configure(responses: list[Any]):
        call_count = {"n": 0}
        captured_calls: list[dict[str, Any]] = []

        def fake_call_model(**kwargs):
            captured_calls.append(kwargs)
            idx = min(call_count["n"], len(responses) - 1)
            call_count["n"] += 1
            result = responses[idx]
            if isinstance(result, Exception):
                raise result
            return result

        monkeypatch.setattr("opik_optimizer._llm_calls.call_model", fake_call_model)
        call_count["calls"] = captured_calls
        return call_count

    return _configure


@pytest.fixture
def mock_llm_sequence_async(monkeypatch: pytest.MonkeyPatch):
    """Async version of mock_llm_sequence."""

    def _configure(responses: list[Any]):
        call_count = {"n": 0}
        captured_calls: list[dict[str, Any]] = []

        async def fake_call_model_async(**kwargs):
            captured_calls.append(kwargs)
            idx = min(call_count["n"], len(responses) - 1)
            call_count["n"] += 1
            result = responses[idx]
            if isinstance(result, Exception):
                raise result
            return result

        monkeypatch.setattr(
            "opik_optimizer._llm_calls.call_model_async", fake_call_model_async
        )
        call_count["calls"] = captured_calls
        return call_count

    return _configure


# ============================================================
# Opik Platform Mocks
# ============================================================


@pytest.fixture
def mock_opik_client(monkeypatch: pytest.MonkeyPatch):
    """
    Mock Opik client to avoid network calls during tests.

    Creates a mock client with commonly used methods pre-configured.
    The mock captures all method calls for assertion.

    Usage:
        def test_optimization(mock_opik_client):
            client = mock_opik_client()

            # Access mock methods
            client.create_optimization.assert_called_once()

            # Customize return values
            client = mock_opik_client(
                optimization_id="custom-opt-id",
                dataset_id="custom-ds-id"
            )
    """

    def _configure(
        *,
        optimization_id: str = "opt-123",
        dataset_id: str = "ds-123",
    ):
        mock_client = MagicMock()

        # Create optimization mock
        mock_optimization = MagicMock()
        mock_optimization.id = optimization_id
        mock_optimization.update = MagicMock()
        mock_client.create_optimization.return_value = mock_optimization

        # Dataset mock
        mock_dataset = MagicMock()
        mock_dataset.id = dataset_id
        mock_client.get_dataset_by_name.return_value = mock_dataset

        # Patch the Opik class
        monkeypatch.setattr("opik.Opik", lambda **kw: mock_client)

        return mock_client

    return _configure


@pytest.fixture
def mock_dataset():
    """
    Factory for creating mock Dataset objects.

    Creates a mock that behaves like an Opik Dataset, with configurable
    items and metadata.

    Usage:
        def test_with_dataset(mock_dataset):
            dataset = mock_dataset([
                {"id": "1", "question": "Q1", "answer": "A1"},
                {"id": "2", "question": "Q2", "answer": "A2"},
            ])

            # With custom name and ID
            dataset = mock_dataset(
                items=[...],
                name="my-dataset",
                dataset_id="custom-id"
            )

            # With nb_samples support
            items = dataset.get_items(nb_samples=1)
    """

    def _create(
        items: list[dict[str, Any]],
        *,
        name: str = "test-dataset",
        dataset_id: str = "dataset-123",
    ):
        mock = MagicMock()
        mock.name = name
        mock.id = dataset_id

        # Handle both get_items() and get_items(nb_samples=N)
        def get_items_impl(nb_samples: int | None = None):
            if nb_samples is not None:
                return items[:nb_samples]
            return items

        mock.get_items = MagicMock(side_effect=get_items_impl)
        return mock

    return _create


# ============================================================
# Rate Limiting Mocks
# ============================================================


@pytest.fixture
def disable_rate_limiting(monkeypatch: pytest.MonkeyPatch):
    """
    Disable rate limiting for fast test execution.

    Replaces the rate limiting decorators with no-ops so tests
    don't have artificial delays.

    Usage:
        def test_fast_execution(disable_rate_limiting):
            # Rate limiting is now disabled for this test
            pass

    Note:
        This fixture is automatically applied - just include it in your
        test function signature.
    """

    def passthrough_decorator(func):
        return func

    def passthrough_factory():
        return passthrough_decorator

    monkeypatch.setattr("opik_optimizer._throttle.rate_limited", passthrough_factory)
    monkeypatch.setattr(
        "opik_optimizer._throttle.rate_limited_async", passthrough_factory
    )


# ============================================================
# Token Counting Mocks
# ============================================================


@pytest.fixture
def mock_token_counter(monkeypatch: pytest.MonkeyPatch):
    """
    Mock LiteLLM token counting for predictable tests.

    Usage:
        def test_context_fitting(mock_token_counter):
            # Fixed token count
            mock_token_counter(100)

            # Dynamic based on input
            mock_token_counter(side_effect=lambda **kw: len(str(kw)) // 4)
    """

    def _configure(
        token_count: int | None = None,
        *,
        side_effect: Any | None = None,
    ):
        def fake_counter(**kwargs):
            if side_effect:
                return side_effect(**kwargs)
            return token_count or 100

        monkeypatch.setattr("litellm.token_counter", fake_counter)
        return fake_counter

    return _configure


# ============================================================
# Common Test Data Fixtures
# ============================================================


@pytest.fixture
def simple_chat_prompt() -> ChatPrompt:
    """
    Basic ChatPrompt for testing.

    A minimal prompt with system and user messages, suitable for most tests.
    """
    return ChatPrompt(
        name="test-prompt",
        system="You are a helpful assistant.",
        user="{question}",
    )


@pytest.fixture
def chat_prompt_with_messages() -> ChatPrompt:
    """
    ChatPrompt using the messages list format.

    Tests the explicit messages array format rather than system/user shorthand.
    """
    return ChatPrompt(
        name="messages-prompt",
        messages=[
            {"role": "system", "content": "You are a helpful assistant."},
            {"role": "user", "content": "Hello, {name}!"},
            {"role": "assistant", "content": "Hello! How can I help you today?"},
            {"role": "user", "content": "{question}"},
        ],
    )


@pytest.fixture
def multimodal_chat_prompt() -> ChatPrompt:
    """
    ChatPrompt with multimodal content for testing.

    Contains image content alongside text for testing multimodal handling.
    """
    return ChatPrompt(
        name="multimodal-prompt",
        messages=[
            {"role": "system", "content": "Analyze the image."},
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "What is in this image?"},
                    {
                        "type": "image_url",
                        "image_url": {"url": "data:image/png;base64,iVBORw0KGgo="},
                    },
                ],
            },
        ],
    )


@pytest.fixture
def chat_prompt_with_tools() -> ChatPrompt:
    """
    ChatPrompt with tool definitions for testing.

    Includes a search tool definition for testing tool-related functionality.
    """
    return ChatPrompt(
        name="tool-prompt",
        system="Use the search tool when needed.",
        user="{query}",
        tools=[
            {
                "type": "function",
                "function": {
                    "name": "search",
                    "description": "Search for information on the web",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "query": {
                                "type": "string",
                                "description": "The search query",
                            }
                        },
                        "required": ["query"],
                    },
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "calculator",
                    "description": "Perform mathematical calculations",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "expression": {
                                "type": "string",
                                "description": "The math expression to evaluate",
                            }
                        },
                        "required": ["expression"],
                    },
                },
            },
        ],
    )


@pytest.fixture
def sample_dataset_items() -> list[dict[str, Any]]:
    """
    Standard dataset items for testing evaluations.

    A small but diverse set of Q&A pairs suitable for most evaluation tests.
    """
    return [
        {"id": "item-1", "question": "What is 2+2?", "answer": "4"},
        {
            "id": "item-2",
            "question": "What is the capital of France?",
            "answer": "Paris",
        },
        {
            "id": "item-3",
            "question": "What is the largest planet?",
            "answer": "Jupiter",
        },
        {
            "id": "item-4",
            "question": "Who wrote Romeo and Juliet?",
            "answer": "Shakespeare",
        },
        {"id": "item-5", "question": "What is H2O?", "answer": "Water"},
    ]


@pytest.fixture
def large_dataset_items() -> list[dict[str, Any]]:
    """
    Larger dataset for testing pagination and batching.

    Contains 50 items for testing scenarios that require more data.
    """
    return [
        {"id": f"item-{i}", "question": f"Question {i}?", "answer": f"Answer {i}"}
        for i in range(50)
    ]


@pytest.fixture
def sample_metric():
    """
    Simple metric function for testing.

    Returns 1.0 if the expected answer is found in the LLM output, 0.0 otherwise.
    Case-insensitive matching.
    """

    def accuracy_metric(
        dataset_item: dict[str, Any], llm_output: dict[str, Any]
    ) -> float:
        expected = dataset_item.get("answer", "").lower()
        actual = str(llm_output.get("llm_output", "")).lower()
        return 1.0 if expected in actual else 0.0

    accuracy_metric.__name__ = "accuracy_metric"
    return accuracy_metric


@pytest.fixture
def sample_metric_with_reason():
    """
    Metric function that returns a score with a reason.

    Useful for testing hierarchical reflective optimizer which requires reasons.
    """

    def accuracy_with_reason(
        dataset_item: dict[str, Any], llm_output: dict[str, Any]
    ) -> dict[str, Any]:
        expected = dataset_item.get("answer", "").lower()
        actual = str(llm_output.get("llm_output", "")).lower()
        score = 1.0 if expected in actual else 0.0
        reason = (
            f"Expected '{expected}' found in output"
            if score == 1.0
            else f"Expected '{expected}' not found in output '{actual[:50]}...'"
        )
        return {"score": score, "reason": reason}

    accuracy_with_reason.__name__ = "accuracy_with_reason"
    return accuracy_with_reason


# ============================================================
# Agent Mocks
# ============================================================


@pytest.fixture
def mock_agent():
    """
    Factory for creating mock OptimizableAgent instances.

    Usage:
        def test_with_agent(mock_agent):
            agent = mock_agent(return_value="LLM response")

            # With dynamic responses based on input
            agent = mock_agent(
                side_effect=lambda prompts, item, **kw: item["answer"]
            )
    """

    def _create(
        return_value: str = "Mock LLM response",
        *,
        side_effect: Any | None = None,
    ):
        mock = MagicMock()

        def invoke_impl(prompts, dataset_item, **kwargs):
            if side_effect:
                return side_effect(prompts, dataset_item, **kwargs)
            return return_value

        mock.invoke_agent = MagicMock(side_effect=invoke_impl)
        return mock

    return _create


# ============================================================
# Evaluation Result Mocks
# ============================================================


@pytest.fixture
def mock_evaluation_result():
    """
    Factory for creating mock EvaluationResult objects.

    Useful for testing optimizers that analyze evaluation results.

    Usage:
        def test_analysis(mock_evaluation_result):
            result = mock_evaluation_result(
                scores=[0.8, 0.6, 0.9, 0.4],
                reasons=["Good", "Missing detail", "Perfect", "Wrong"]
            )
    """

    def _create(
        scores: list[float],
        *,
        reasons: list[str] | None = None,
        dataset_item_ids: list[str] | None = None,
    ):
        from unittest.mock import MagicMock

        mock_result = MagicMock()
        test_results = []

        for i, score in enumerate(scores):
            test_result = MagicMock()
            test_case = MagicMock()
            test_case.dataset_item_id = (
                dataset_item_ids[i] if dataset_item_ids else f"item-{i}"
            )
            test_result.test_case = test_case
            test_result.trial_id = f"trial-{i}"

            score_result = MagicMock()
            score_result.name = "accuracy"
            score_result.value = score
            score_result.reason = reasons[i] if reasons else None
            score_result.scoring_failed = False

            test_result.score_results = [score_result]
            test_results.append(test_result)

        mock_result.test_results = test_results
        return mock_result

    return _create
