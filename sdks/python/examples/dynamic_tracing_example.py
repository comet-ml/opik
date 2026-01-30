"""
Dynamic Tracing Control Example

This example demonstrates how to enable and disable Opik tracing at runtime
without modifying your instrumented code or restarting your application.
"""

import time
from typing import Dict, Any

import opik
from opik.integrations import openai as openai_integration


def simulate_openai_client() -> object:
    """Create a mock OpenAI client for demonstration."""

    class MockClient:
        def __init__(self) -> None:
            self.chat = type(
                "Chat",
                (),
                {
                    "completions": type(
                        "Completions",
                        (),
                        {"create": lambda self, **kwargs: {"content": "Mock response"}},
                    )()
                },
            )()

        def __getattr__(self, name: str) -> Any:
            return None

    return MockClient()


@opik.track(name="llm_call")
def call_llm(prompt: str, user_type: str = "free") -> str:
    """Simulate an LLM call with user type information."""
    client = simulate_openai_client()
    response = client.chat.completions.create(
        model="gpt-3.5-turbo", messages=[{"role": "user", "content": prompt}]
    )
    return f"Response for {user_type} user: {response['content']}"


@opik.track(name="data_processing")
def process_data(data: Dict[str, Any]) -> Dict[str, Any]:
    """Simulate data processing that we want to trace."""
    result = {"processed": True, "item_count": len(data)}
    time.sleep(0.01)  # Simulate work
    return result


def measure_performance(func, *args, iterations: int = 100) -> float:
    """Measure average execution time of a function."""
    start_time = time.time()
    for _ in range(iterations):
        func(*args)
    end_time = time.time()
    return (end_time - start_time) / iterations


def main() -> None:
    """Demonstrate dynamic tracing capabilities."""

    print("=== Opik Dynamic Tracing Demo ===\n")

    # 1. Basic enable/disable functionality
    print("1. Basic Runtime Control")
    print("-" * 30)

    print(f"Initial tracing state: {opik.is_tracing_active()}")

    # Disable tracing
    opik.set_tracing_active(False)
    print(f"After disabling: {opik.is_tracing_active()}")

    # Call traced function - no traces will be created
    result = call_llm("Hello world", "free")
    print(f"Function result (no tracing): {result}")

    # Re-enable tracing
    opik.set_tracing_active(True)
    print(f"After enabling: {opik.is_tracing_active()}\n")

    # 2. Conditional tracing based on user type
    print("2. Conditional Tracing by User Type")
    print("-" * 40)

    def handle_request(prompt: str, user_type: str) -> str:
        """Handle request with conditional tracing."""
        # Only trace premium users
        should_trace = user_type == "premium"
        opik.set_tracing_active(should_trace)

        print(f"Processing {user_type} user request (tracing: {should_trace})")
        return call_llm(prompt, user_type)

    # Process different user types
    handle_request("What is AI?", "free")
    handle_request("Explain quantum computing", "premium")
    handle_request("Hello", "free")
    print()

    # 3. Sampling-based tracing
    print("3. Sampling-Based Tracing (10% of requests)")
    print("-" * 50)

    import random

    def handle_request_with_sampling(request_id: int) -> Dict[str, Any]:
        """Handle request with 10% sampling rate."""
        should_trace = random.random() < 0.1  # 10% sampling
        opik.set_tracing_active(should_trace)

        data = {"request_id": request_id, "data": list(range(10))}
        result = process_data(data)

        if should_trace:
            print(f"Request {request_id}: TRACED")
        else:
            print(f"Request {request_id}: not traced")

        return result

    # Process multiple requests
    for i in range(10):
        handle_request_with_sampling(i)
    print()

    # 4. Performance comparison
    print("4. Performance Impact Comparison")
    print("-" * 40)

    test_data = {"items": list(range(100))}

    # Measure with tracing enabled
    opik.set_tracing_active(True)
    time_with_tracing = measure_performance(process_data, test_data, iterations=50)

    # Measure with tracing disabled
    opik.set_tracing_active(False)
    time_without_tracing = measure_performance(process_data, test_data, iterations=50)

    print(f"Average time with tracing: {time_with_tracing * 1000:.2f}ms")
    print(f"Average time without tracing: {time_without_tracing * 1000:.2f}ms")

    if time_with_tracing > time_without_tracing:
        overhead = (
            (time_with_tracing - time_without_tracing) / time_without_tracing
        ) * 100
        print(f"Tracing overhead: {overhead:.1f}%")
    print()

    # 5. Integration tracking control
    print("5. Integration Tracking Control")
    print("-" * 40)

    # Simulate tracking an OpenAI client
    mock_client = simulate_openai_client()

    # Disable tracing before setting up integration
    opik.set_tracing_active(False)
    openai_integration.track_openai(mock_client)
    print(
        "OpenAI client tracking setup with tracing disabled - no instrumentation applied"
    )

    # Enable tracing and set up integration
    opik.set_tracing_active(True)
    openai_integration.track_openai(mock_client)
    print("OpenAI client tracking setup with tracing enabled - instrumentation applied")
    print()

    # 6. Reset to configuration default
    print("6. Reset to Configuration Default")
    print("-" * 40)

    # Override runtime setting
    opik.set_tracing_active(False)
    print(f"Runtime override active: {opik.is_tracing_active()}")

    # Reset to config default
    opik.reset_tracing_to_config_default()
    print(f"After reset to config: {opik.is_tracing_active()}")
    print("(This will use the value from OPIK_TRACK_DISABLE or config file)")

    print("\n=== Demo Complete ===")
    print("Key benefits of dynamic tracing:")
    print("• Zero code changes required")
    print("• Runtime performance optimization")
    print("• Flexible sampling strategies")
    print("• Easy debugging and troubleshooting")


if __name__ == "__main__":
    main()
