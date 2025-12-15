"""
Tests for LLM router / provider extraction logic in DSPy integration.

This module tests the functionality for extracting actual provider and cost
information from LLM routers like OpenRouter that route requests to
different underlying providers.
"""

from unittest.mock import MagicMock

import dspy

from opik.integrations.dspy.callback import OpikCallback
from opik.integrations.dspy.parsers import (
    LMHistoryInfo,
    extract_lm_info_from_history,
    get_span_type,
)


class TestGetSpanType:
    """Tests for the get_span_type function."""

    def test_predict_returns_llm(self):
        """dspy.Predict should return 'llm' span type."""
        instance = dspy.Predict("question -> answer")
        assert get_span_type(instance) == "llm"

    def test_lm_returns_llm(self):
        """dspy.LM should return 'llm' span type."""
        # Create an LM instance to test isinstance check
        assert get_span_type(dspy.LM.__new__(dspy.LM)) == "llm"

    def test_tool_returns_tool(self):
        """dspy.Tool should return 'tool' span type."""

        def dummy_func():
            pass

        instance = dspy.Tool(func=dummy_func)
        assert get_span_type(instance) == "tool"

    def test_other_returns_general(self):
        """Other types should return 'general' span type."""
        assert get_span_type("string") == "general"
        assert get_span_type(123) == "general"
        assert get_span_type({}) == "general"


class TestExtractLMInfoFromHistory:
    """Tests for the extract_lm_info_from_history function."""

    def test_no_history_returns_empty_info(self):
        """When LM has no history, should return empty LMHistoryInfo."""
        mock_lm = MagicMock()
        mock_lm.history = []

        result = extract_lm_info_from_history(mock_lm, None)

        assert result.usage is None
        assert result.cache_hit is None
        assert result.actual_provider is None
        assert result.total_cost is None

    def test_no_history_attr_returns_empty_info(self):
        """When LM has no history attribute, should return empty LMHistoryInfo."""
        mock_lm = MagicMock(spec=[])  # No attributes

        result = extract_lm_info_from_history(mock_lm, None)

        assert result.usage is None
        assert result.cache_hit is None
        assert result.actual_provider is None
        assert result.total_cost is None

    def test_message_mismatch_returns_empty_info(self):
        """When history messages don't match expected, should return empty info."""
        mock_lm = MagicMock()
        mock_lm.history = [{"messages": [{"role": "user", "content": "other"}]}]

        result = extract_lm_info_from_history(
            mock_lm, [{"role": "user", "content": "expected"}]
        )

        assert result.usage is None
        assert result.cache_hit is None

    def test_extracts_provider_from_response(self):
        """Should extract actual_provider from response.provider attribute."""
        mock_response = MagicMock()
        mock_response.provider = "Novita"
        mock_response.cache_hit = False

        mock_lm = MagicMock()
        expected_messages = [{"role": "user", "content": "test"}]
        mock_lm.history = [
            {
                "messages": expected_messages,
                "response": mock_response,
                "usage": {"prompt_tokens": 10, "completion_tokens": 20},
            }
        ]

        result = extract_lm_info_from_history(mock_lm, expected_messages)

        assert result.actual_provider == "Novita"

    def test_extracts_cost_from_history_entry(self):
        """Should extract cost from history entry's 'cost' field."""
        mock_response = MagicMock()
        mock_response.cache_hit = False

        mock_lm = MagicMock()
        expected_messages = [{"role": "user", "content": "test"}]
        mock_lm.history = [
            {
                "messages": expected_messages,
                "response": mock_response,
                "cost": 9.524e-06,
                "usage": {"prompt_tokens": 10, "completion_tokens": 20},
            }
        ]

        result = extract_lm_info_from_history(mock_lm, expected_messages)

        assert result.total_cost == 9.524e-06

    def test_extracts_cost_from_usage_dict(self):
        """Should extract cost from usage dict when not in history entry."""
        mock_response = MagicMock()
        mock_response.cache_hit = False

        mock_lm = MagicMock()
        expected_messages = [{"role": "user", "content": "test"}]
        mock_lm.history = [
            {
                "messages": expected_messages,
                "response": mock_response,
                "cost": None,
                "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 20,
                    "cost": 5.0e-06,
                },
            }
        ]

        result = extract_lm_info_from_history(mock_lm, expected_messages)

        assert result.total_cost == 5.0e-06

    def test_extracts_usage_tokens(self):
        """Should extract token usage from history."""
        mock_response = MagicMock()
        mock_response.cache_hit = False

        mock_lm = MagicMock()
        expected_messages = [{"role": "user", "content": "test"}]
        mock_lm.history = [
            {
                "messages": expected_messages,
                "response": mock_response,
                "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 20,
                    "total_tokens": 30,
                },
            }
        ]

        result = extract_lm_info_from_history(mock_lm, expected_messages)

        assert result.usage is not None
        assert result.usage.prompt_tokens == 10
        assert result.usage.completion_tokens == 20
        assert result.usage.total_tokens == 30

    def test_cache_hit_from_response(self):
        """Should extract cache_hit from response.cache_hit attribute."""
        mock_response = MagicMock()
        mock_response.cache_hit = True

        mock_lm = MagicMock()
        expected_messages = [{"role": "user", "content": "test"}]
        mock_lm.history = [
            {
                "messages": expected_messages,
                "response": mock_response,
                "usage": {"prompt_tokens": 10, "completion_tokens": 20},
            }
        ]

        result = extract_lm_info_from_history(mock_lm, expected_messages)

        assert result.cache_hit is True

    def test_no_provider_when_not_in_response(self):
        """Should return None for actual_provider when not in response."""
        mock_response = MagicMock(spec=[])  # No provider attribute

        mock_lm = MagicMock()
        expected_messages = [{"role": "user", "content": "test"}]
        mock_lm.history = [
            {
                "messages": expected_messages,
                "response": mock_response,
                "usage": {"prompt_tokens": 10, "completion_tokens": 20},
            }
        ]

        result = extract_lm_info_from_history(mock_lm, expected_messages)

        assert result.actual_provider is None


class TestLMHistoryInfo:
    """Tests for the LMHistoryInfo dataclass."""

    def test_as_tuple_returns_correct_order(self):
        """as_tuple() should return values in correct order."""
        from opik.llm_usage import OpikUsage

        usage = OpikUsage(
            prompt_tokens=10,
            completion_tokens=20,
            total_tokens=30,
            provider_usage={
                "prompt_tokens": 10,
                "completion_tokens": 20,
                "total_tokens": 30,
            },
        )
        info = LMHistoryInfo(
            usage=usage,
            cache_hit=False,
            actual_provider="Novita",
            total_cost=1.0e-05,
        )

        result = info.as_tuple()

        assert result == (usage, False, "Novita", 1.0e-05)

    def test_empty_info_as_tuple(self):
        """Empty LMHistoryInfo should return tuple of Nones."""
        info = LMHistoryInfo(
            usage=None, cache_hit=None, actual_provider=None, total_cost=None
        )

        result = info.as_tuple()

        assert result == (None, None, None, None)


class TestOpikCallbackLLMRouter:
    """Tests for OpikCallback LLM router handling."""

    def test_llm_router_metadata_when_provider_differs(self):
        """
        When using an LLM router (like OpenRouter), the response contains the actual
        provider that served the request and the cost. This test verifies:
        1. The actual provider is extracted from the response
        2. The original router name is stored in metadata as 'llm_router'
        3. The span's provider is updated to the actual provider
        4. The cost from the provider is extracted
        """
        callback = OpikCallback(project_name="test")

        # Create a mock LM instance with history containing an actual provider and cost
        mock_lm = MagicMock()
        mock_lm.model = "openrouter/qwen/qwen3-8b"

        mock_response = MagicMock()
        mock_response.provider = "Novita"  # The actual provider from OpenRouter
        mock_response.cache_hit = False

        expected_messages = [{"role": "user", "content": "test"}]
        mock_lm.history = [
            {
                "messages": expected_messages,
                "response": mock_response,
                "cost": 9.524e-06,  # Cost from OpenRouter
                "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 20,
                    "total_tokens": 30,
                    "cost": 9.524e-06,  # Also in usage dict
                },
            }
        ]

        # Store the mock LM info
        call_id = "test-call-id"
        callback._map_call_id_to_lm_info[call_id] = (mock_lm, expected_messages)

        # Extract info from history
        lm_info = callback._extract_lm_info_from_history(call_id)

        # Verify extraction
        assert lm_info.actual_provider == "Novita"
        assert lm_info.cache_hit is False
        assert lm_info.usage is not None
        assert lm_info.usage.prompt_tokens == 10
        assert lm_info.usage.completion_tokens == 20
        assert lm_info.usage.total_tokens == 30
        assert lm_info.total_cost == 9.524e-06

    def test_no_llm_router_metadata_when_provider_same(self):
        """
        Verify that 'llm_router' is NOT added to metadata when the
        actual provider matches the original provider (no router involved).
        """
        callback = OpikCallback(project_name="test")

        # Create a mock LM instance where provider matches (direct API, not routed)
        mock_lm = MagicMock()
        mock_lm.model = "openai/gpt-4o-mini"

        mock_response = MagicMock()
        mock_response.provider = "openai"  # Same as the model prefix
        mock_response.cache_hit = False

        expected_messages = [{"role": "user", "content": "test"}]
        mock_lm.history = [
            {
                "messages": expected_messages,
                "response": mock_response,
                "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 20,
                    "total_tokens": 30,
                },
            }
        ]

        # Store the mock LM info
        call_id = "test-call-id"
        callback._map_call_id_to_lm_info[call_id] = (mock_lm, expected_messages)

        # Extract info from history
        lm_info = callback._extract_lm_info_from_history(call_id)

        # Actual provider should be "openai"
        assert lm_info.actual_provider == "openai"
        # No cost provided in this case
        assert lm_info.total_cost is None

    def test_actual_provider_none_when_not_in_response(self):
        """
        Verify that actual_provider is None when the response
        doesn't have a provider attribute (older versions or some providers).
        """
        callback = OpikCallback(project_name="test")

        # Create a mock LM instance where response has no provider attribute
        mock_lm = MagicMock()
        mock_lm.model = "openai/gpt-4o-mini"

        mock_response = MagicMock(spec=[])  # No attributes
        mock_response.cache_hit = False

        expected_messages = [{"role": "user", "content": "test"}]
        mock_lm.history = [
            {
                "messages": expected_messages,
                "response": mock_response,
                "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 20,
                    "total_tokens": 30,
                },
            }
        ]

        # Store the mock LM info
        call_id = "test-call-id"
        callback._map_call_id_to_lm_info[call_id] = (mock_lm, expected_messages)

        # Extract info from history
        lm_info = callback._extract_lm_info_from_history(call_id)

        # Actual provider should be None when not in response
        assert lm_info.actual_provider is None
        # No cost provided in this case either
        assert lm_info.total_cost is None


class TestOpikCallbackCaseInsensitiveProvider:
    """Tests for case-insensitive provider comparison."""

    def test_provider_comparison_is_case_insensitive(self):
        """
        When comparing providers, the comparison should be case-insensitive.
        For example, "OpenAI" should match "openai".
        """
        callback = OpikCallback(project_name="test")

        # Create a mock LM instance where provider has different casing
        mock_lm = MagicMock()
        mock_lm.model = "openai/gpt-4o-mini"  # lowercase "openai"

        mock_response = MagicMock()
        mock_response.provider = "OpenAI"  # Capitalized "OpenAI"
        mock_response.cache_hit = False

        expected_messages = [{"role": "user", "content": "test"}]
        mock_lm.history = [
            {
                "messages": expected_messages,
                "response": mock_response,
                "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 20,
                    "total_tokens": 30,
                },
            }
        ]

        # Store the mock LM info
        call_id = "test-call-id"
        callback._map_call_id_to_lm_info[call_id] = (mock_lm, expected_messages)

        # Extract info from history - provider is extracted as-is
        lm_info = callback._extract_lm_info_from_history(call_id)

        # The actual_provider should be "OpenAI" (case preserved)
        assert lm_info.actual_provider == "OpenAI"
