"""
Shared utilities for optimizer e2e tests.

This package provides:
- Mock tools and tool definitions
- Test agents for multi-prompt testing
- Assertion helpers for validation
"""

from .mock_tools import (
    mock_calculator,
    mock_search,
    mock_weather,
    CALCULATOR_TOOL,
    SEARCH_TOOL,
    WEATHER_TOOL,
)
from .test_agent import TestMultiPromptAgent
from .assertions import (
    assert_tools_preserved,
    assert_prompt_changed,
    assert_multimodal_structure_preserved,
    assert_multi_prompt_changed,
)

__all__ = [
    # Mock tools
    "mock_calculator",
    "mock_search",
    "mock_weather",
    "CALCULATOR_TOOL",
    "SEARCH_TOOL",
    "WEATHER_TOOL",
    # Test agent
    "TestMultiPromptAgent",
    # Assertions
    "assert_tools_preserved",
    "assert_prompt_changed",
    "assert_multimodal_structure_preserved",
    "assert_multi_prompt_changed",
]


