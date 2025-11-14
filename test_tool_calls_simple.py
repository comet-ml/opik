#!/usr/bin/env python3
"""
Simple test script to quickly create a single trace with tool calls.
"""

import opik
import time

client = opik.Opik()

# Generate a unique thread_id
thread_id = f"simple_test_{int(time.time())}"

# Create a trace with tool calls
trace = client.trace(
    name="weather_assistant",
    input={
        "messages": [
            {"role": "user", "content": "What's the weather in Boston?"}
        ]
    },
    output={
        "messages": [
            {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "id": "call_123",
                        "type": "function",
                        "function": {
                            "name": "get_weather",
                            "arguments": '{"location": "Boston", "unit": "celsius"}'
                        }
                    }
                ]
            },
            {
                "role": "tool",
                "tool_call_id": "call_123",
                "content": '{"temperature": 22, "condition": "sunny"}'
            },
            {
                "role": "assistant",
                "content": "The weather in Boston is sunny with 22°C."
            }
        ]
    },
    project_name="Tool Calls Test",
    thread_id=thread_id
)

print(f"✅ Created trace with tool calls: {trace.id}")
print(f"Thread ID: {thread_id}")
print(f"View it in the Opik UI under project 'Tool Calls Test'")

