#!/usr/bin/env python3
"""
Test script to create traces with tool calls in a thread.
This demonstrates the tool call icons feature in the thread view UI.

Usage:
    python test_tool_calls_thread.py
"""

import opik
from datetime import datetime
import time

# Initialize Opik client
client = opik.Opik()

# Create a unique thread_id for this test session
thread_id = f"test_thread_{int(time.time())}"
project_name = "Tool Calls Thread Test"

print(f"Creating test thread: {thread_id}")
print(f"Project: {project_name}")
print("=" * 60)

# Test Case 1: Trace with tool calls in output (OpenAI format)
print("\n1. Creating trace with tool calls in output (OpenAI format)...")
trace1 = client.trace(
    name="weather_query",
    input={"messages": [{"role": "user", "content": "What's the weather in Boston?"}]},
    output={
        "messages": [
            {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "id": "call_abc123",
                        "type": "function",
                        "function": {
                            "name": "get_weather",
                            "arguments": '{"location": "Boston", "unit": "celsius"}'
                        }
                    }
                ]
            }
        ]
    },
    project_name=project_name,
    thread_id=thread_id,
    metadata={"test_case": "openai_tool_calls"}
)
print(f"✓ Created trace: {trace1.id}")

time.sleep(0.5)

# Test Case 2: Trace with tool response and final answer
print("\n2. Creating trace with tool response and final answer...")
trace2 = client.trace(
    name="weather_response",
    input={"messages": [{"role": "user", "content": "What's the weather in Boston?"}]},
    output={
        "messages": [
            {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "id": "call_def456",
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
                "tool_call_id": "call_def456",
                "content": '{"temperature": 22, "condition": "sunny", "humidity": 65}'
            },
            {
                "role": "assistant",
                "content": "The weather in Boston is currently sunny with a temperature of 22°C and 65% humidity."
            }
        ]
    },
    project_name=project_name,
    thread_id=thread_id,
    metadata={"test_case": "tool_response"}
)
print(f"✓ Created trace: {trace2.id}")

time.sleep(0.5)

# Test Case 3: Trace with multiple tool calls
print("\n3. Creating trace with multiple tool calls...")
trace3 = client.trace(
    name="multi_tool_query",
    input={"messages": [{"role": "user", "content": "Book a flight to LAX and reserve a hotel"}]},
    output={
        "messages": [
            {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "id": "call_flight_001",
                        "type": "function",
                        "function": {
                            "name": "book_flight",
                            "arguments": '{"from": "BOS", "to": "LAX", "date": "2024-12-01"}'
                        }
                    },
                    {
                        "id": "call_hotel_001",
                        "type": "function",
                        "function": {
                            "name": "book_hotel",
                            "arguments": '{"location": "LAX", "checkin": "2024-12-01", "nights": 3}'
                        }
                    }
                ]
            }
        ]
    },
    project_name=project_name,
    thread_id=thread_id,
    metadata={"test_case": "multiple_tools"}
)
print(f"✓ Created trace: {trace3.id}")

time.sleep(0.5)

# Test Case 4: Trace with tool calls in input (alternative format)
print("\n4. Creating trace with tool calls in input...")
trace4 = client.trace(
    name="search_query",
    input={
        "tool_calls": [
            {
                "id": "call_search_789",
                "type": "function",
                "function": {
                    "name": "web_search",
                    "arguments": '{"query": "latest AI news", "max_results": 5}'
                }
            }
        ]
    },
    output={
        "content": "Here are the latest AI news articles from the search results...",
        "articles": ["Article 1", "Article 2", "Article 3"]
    },
    project_name=project_name,
    thread_id=thread_id,
    metadata={"test_case": "input_tool_calls"}
)
print(f"✓ Created trace: {trace4.id}")

time.sleep(0.5)

# Test Case 5: Regular trace without tool calls (for comparison)
print("\n5. Creating regular trace without tool calls...")
trace5 = client.trace(
    name="simple_conversation",
    input={"messages": [{"role": "user", "content": "Hello! How are you?"}]},
    output={
        "messages": [
            {
                "role": "assistant",
                "content": "I'm doing well, thank you for asking! How can I help you today?"
            }
        ]
    },
    project_name=project_name,
    thread_id=thread_id,
    metadata={"test_case": "no_tools"}
)
print(f"✓ Created trace: {trace5.id}")

time.sleep(0.5)

# Test Case 6: Complex agent workflow with multiple tool interactions
print("\n6. Creating complex agent workflow trace...")
trace6 = client.trace(
    name="agent_workflow",
    input={"messages": [{"role": "user", "content": "Analyze the sales data and create a report"}]},
    output={
        "messages": [
            {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "id": "call_db_001",
                        "type": "function",
                        "function": {
                            "name": "query_database",
                            "arguments": '{"query": "SELECT * FROM sales WHERE date >= \'2024-01-01\'"}'
                        }
                    }
                ]
            },
            {
                "role": "tool",
                "tool_call_id": "call_db_001",
                "content": '{"rows": 1250, "total_revenue": 567890.50}'
            },
            {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "id": "call_analyze_001",
                        "type": "function",
                        "function": {
                            "name": "analyze_data",
                            "arguments": '{"data_summary": "1250 sales totaling $567,890.50"}'
                        }
                    }
                ]
            },
            {
                "role": "tool",
                "tool_call_id": "call_analyze_001",
                "content": '{"insights": ["20% growth YoY", "Top product: Widget A", "Peak sales: Q4"]}'
            },
            {
                "role": "assistant",
                "content": "Based on the analysis of 1,250 sales totaling $567,890.50, I've generated the following insights:\n\n1. **20% growth year-over-year** - Strong performance\n2. **Top product: Widget A** - Focus inventory here\n3. **Peak sales: Q4** - Plan for seasonal demand\n\nThe report has been prepared with detailed breakdowns."
            }
        ]
    },
    project_name=project_name,
    thread_id=thread_id,
    metadata={"test_case": "complex_agent"}
)
print(f"✓ Created trace: {trace6.id}")

print("\n" + "=" * 60)
print("✅ Test complete! Created 6 traces with various tool call scenarios")
print(f"\nTo view the thread in the UI:")
print(f"1. Go to the Opik UI")
print(f"2. Navigate to project: '{project_name}'")
print(f"3. Go to the Threads tab")
print(f"4. Look for thread_id: {thread_id}")
print(f"\nExpected behavior:")
print("- Traces 1-4 and 6 should show tool call icons")
print("- Trace 5 should NOT show tool call icons")
print("- Icons should appear on both input and output message bubbles")
print("=" * 60)

