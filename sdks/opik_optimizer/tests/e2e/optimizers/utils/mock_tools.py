"""
Mock tools and tool definitions for optimizer e2e tests.

This module provides:
- Simple mock functions that return predictable values
- OpenAI-style tool schema definitions
"""

from typing import Any


# -----------------------------------------------------------------------------
# Mock Tool Functions
# -----------------------------------------------------------------------------


def mock_calculator(operation: str, a: float, b: float) -> float:
    """
    A mock calculator tool that performs basic arithmetic.
    Returns predictable values for testing.
    """
    operations = {
        "add": a + b,
        "subtract": a - b,
        "multiply": a * b,
        "divide": a / b if b != 0 else 0.0,
    }
    return operations.get(operation, 0.0)


def mock_search(query: str) -> str:
    """
    A mock search tool that returns predictable results.
    """
    return f"Search results for: {query}. Found 3 relevant documents."


def mock_weather(location: str) -> str:
    """
    A mock weather tool that returns predictable weather data.
    """
    return f"Weather in {location}: Sunny, 72°F (22°C), Humidity: 45%"


# -----------------------------------------------------------------------------
# Tool Definitions (OpenAI-style schemas)
# -----------------------------------------------------------------------------

CALCULATOR_TOOL: dict[str, Any] = {
    "type": "function",
    "function": {
        "name": "mock_calculator",
        "description": "Performs basic arithmetic operations",
        "parameters": {
            "type": "object",
            "properties": {
                "operation": {
                    "type": "string",
                    "enum": ["add", "subtract", "multiply", "divide"],
                    "description": "The arithmetic operation to perform",
                },
                "a": {
                    "type": "number",
                    "description": "First operand",
                },
                "b": {
                    "type": "number",
                    "description": "Second operand",
                },
            },
            "required": ["operation", "a", "b"],
        },
    },
}

SEARCH_TOOL: dict[str, Any] = {
    "type": "function",
    "function": {
        "name": "mock_search",
        "description": "Searches for information on a given query",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The search query",
                },
            },
            "required": ["query"],
        },
    },
}

WEATHER_TOOL: dict[str, Any] = {
    "type": "function",
    "function": {
        "name": "mock_weather",
        "description": "Gets weather information for a location",
        "parameters": {
            "type": "object",
            "properties": {
                "location": {
                    "type": "string",
                    "description": "The location to get weather for",
                },
            },
            "required": ["location"],
        },
    },
}
