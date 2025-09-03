#!/usr/bin/env python3
"""
Test script demonstrating the Claude Code integration with a real-like structure.

This simulates the actual usage you showed in your test file to verify
that the integration properly handles the conversation flow.
"""

import asyncio
from typing import AsyncGenerator, Any, List


# Mock the actual claude_code_sdk classes to match your real usage
class TextBlock:
    def __init__(self, text: str):
        self.text = text


class AssistantMessage:
    def __init__(self, content: List[TextBlock]):
        self.content = content


class ResultMessage:
    def __init__(self, total_cost_usd: float = 0.0):
        self.total_cost_usd = total_cost_usd


class SystemMessage:
    def __init__(self, content: str):
        self.content = content


class UserMessage:
    def __init__(self, content: str):
        self.content = content


# Mock Claude Code query function that simulates your real usage
async def mock_query(prompt: str, options=None) -> AsyncGenerator[Any, None]:
    """Mock function that simulates the real Claude Code conversation flow you showed."""

    # Simulate the complex conversation flow you showed
    yield SystemMessage("System initialized")

    yield AssistantMessage(
        [
            TextBlock(
                "I'll create a file called `hello.txt` with the content 'Hello, World!' for you."
            )
        ]
    )

    yield AssistantMessage(
        [
            TextBlock(
                "Let me check the current directory first to create the file with an absolute path:"
            )
        ]
    )

    yield UserMessage("pwd command executed")

    yield AssistantMessage(
        [TextBlock("Now I'll create the file with the absolute path:")]
    )

    yield UserMessage("write file command executed")

    yield AssistantMessage(
        [
            TextBlock(
                "Since this is a new file, let me try reading it first (even though it doesn't exist yet) and then create it:"
            )
        ]
    )

    yield UserMessage("read file command executed")

    yield AssistantMessage(
        [
            TextBlock(
                "It appears the file already exists with the content 'Hello, World!' in it. The file has been successfully created with the requested content."
            )
        ]
    )

    yield ResultMessage(total_cost_usd=0.0234)


async def test_claude_code_integration():
    """Test the Claude Code integration."""
    print("Testing Claude Code integration...")

    # Import the integration (this would normally be from claude_code_sdk import query)
    from opik.integrations.claude_code import track_claude_code

    # Track the query function
    tracked_query = track_claude_code(mock_query)

    print("\nStarting Claude Code conversation...")
    messages = []
    async for message in tracked_query(
        "Create a file called hello.txt with 'Hello, World!' in it"
    ):
        messages.append(message)
        print(f"message {type(message).__name__}")

        if isinstance(message, AssistantMessage):
            for block in message.content:
                if isinstance(block, TextBlock):
                    print(f"Claude: {block.text}")
        elif isinstance(message, ResultMessage) and message.total_cost_usd > 0:
            print(f"\nCost: ${message.total_cost_usd:.4f}")

    print(f"\nProcessed {len(messages)} messages total")

    # Flush to ensure all traces are sent
    import opik

    client = opik.Opik()
    client.flush()

    print("✅ Integration test completed! Check the Opik UI for the trace.")
    print("🔍 You should see:")
    print("  - A main 'claude_code_query' trace")
    print("  - Structured conversation flow in the output")
    print("  - Individual assistant messages clearly readable")
    print("  - Cost information and conversation summary")


if __name__ == "__main__":
    # Configure Opik (assumes you have OPIK_API_KEY set)
    import opik

    try:
        opik.configure()
        asyncio.run(test_claude_code_integration())
    except Exception as e:
        print(f"❌ Test failed: {e}")
        print("💡 Make sure you have configured Opik (run `opik configure`)")
