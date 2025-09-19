#!/usr/bin/env python3
"""
Example demonstrating Opik integration with Claude Code Python SDK.

This example shows how to:
1. Configure Opik to track Claude Code calls
2. Use the tracked query function
3. Handle async generators
4. Track cost information
5. Use with custom options

Prerequisites:
- pip install opik claude-code-sdk
- export ANTHROPIC_API_KEY="your-api-key"
"""

import asyncio
import opik
from opik.integrations.claude_code import (
    track_claude_code,
    track_claude_code_with_options,
)

# Note: This example uses mock objects since claude-code-sdk might not be available
# In a real scenario, you would import from claude_code_sdk:
# from claude_code_sdk import query, ClaudeCodeOptions


# Mock classes for demonstration (replace with real claude_code_sdk imports)
class MockTextBlock:
    def __init__(self, text: str):
        self.text = text


class MockAssistantMessage:
    def __init__(self, content: list):
        self.content = content


class MockResultMessage:
    def __init__(self, total_cost_usd: float = 0.0):
        self.total_cost_usd = total_cost_usd


class MockClaudeCodeOptions:
    def __init__(self, system_prompt=None, allowed_tools=None, max_turns=None):
        self.system_prompt = system_prompt
        self.allowed_tools = allowed_tools
        self.max_turns = max_turns


# Mock query function (replace with real claude_code_sdk.query)
async def mock_query(prompt: str, options=None):
    """Mock query function for demonstration."""
    yield MockAssistantMessage([MockTextBlock(f"Processing: {prompt}")])
    yield MockAssistantMessage(
        [MockTextBlock("Here's your generated code: def example(): pass")]
    )
    yield MockResultMessage(total_cost_usd=0.0023)


async def basic_example():
    """Basic Claude Code tracking example."""
    print("=== Basic Claude Code Tracking Example ===")

    # Configure Opik
    opik.configure()

    # Track the Claude Code query function
    tracked_query = track_claude_code(mock_query, project_name="claude-code-demo")

    # Use the tracked function
    async for message in tracked_query("Create a simple Python function"):
        if hasattr(message, "content"):
            for block in message.content:
                if hasattr(block, "text"):
                    print(f"Claude: {block.text}")
        elif hasattr(message, "total_cost_usd"):
            print(f"Cost: ${message.total_cost_usd:.4f}")

    print("✓ Basic example completed - check Opik UI for traces\n")


async def advanced_example():
    """Advanced example with custom options and tracking."""
    print("=== Advanced Claude Code Tracking Example ===")

    # Track with custom configuration
    tracked_query = track_claude_code_with_options(
        mock_query,
        project_name="claude-code-advanced",
        span_name="code_generation",
        tags=["code", "python", "assistant"],
        metadata={"version": "1.0", "model": "claude-3"},
    )

    # Use with Claude Code options
    options = MockClaudeCodeOptions(
        system_prompt="You are an expert Python developer. Always include docstrings.",
        allowed_tools=["Read", "Write"],
        max_turns=3,
    )

    async for message in tracked_query(
        "Create a function to calculate the factorial of a number", options=options
    ):
        if hasattr(message, "content"):
            for block in message.content:
                if hasattr(block, "text"):
                    print(f"Claude: {block.text}")
        elif hasattr(message, "total_cost_usd"):
            print(f"Cost: ${message.total_cost_usd:.4f}")

    print("✓ Advanced example completed - check Opik UI for detailed traces\n")


@opik.track
async def nested_tracking_example():
    """Example showing nested tracking with @opik.track decorator."""
    print("=== Nested Tracking Example ===")

    tracked_query = track_claude_code(mock_query)

    # This will create a parent span for the entire function
    results = []
    async for message in tracked_query("Generate a simple web scraper"):
        results.append(message)

    return {"message_count": len(results), "status": "completed"}


async def error_handling_example():
    """Example showing error handling."""
    print("=== Error Handling Example ===")

    # Mock function that raises an error
    async def mock_error_query(prompt: str, options=None):
        yield MockAssistantMessage([MockTextBlock("Starting task...")])
        raise ValueError("Mock error for demonstration")

    tracked_query = track_claude_code(mock_error_query)

    try:
        async for message in tracked_query("This will fail"):
            print(f"Received: {message}")
    except ValueError as e:
        print(f"Caught expected error: {e}")
        print("✓ Error was properly tracked in Opik\n")


async def main():
    """Run all examples."""
    print("Claude Code + Opik Integration Examples")
    print("=" * 50)

    try:
        await basic_example()
        await advanced_example()

        result = await nested_tracking_example()
        print(f"✓ Nested tracking completed: {result}\n")

        await error_handling_example()

        print("All examples completed successfully!")
        print("Check your Opik dashboard to see the traced calls.")

    except Exception as e:
        print(f"Error running examples: {e}")
        print("Make sure you have configured Opik properly with: opik.configure()")


if __name__ == "__main__":
    # Run the examples
    asyncio.run(main())
