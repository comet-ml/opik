# %%
import time
import random
from typing import Dict, Any, List
from contextlib import contextmanager

import opik
from opik.integrations.openai import track_openai

# %% [markdown]
"""
# Dynamic Tracing Control Cookbook

This cookbook demonstrates how to use Opik's dynamic tracing control features
to optimize performance and implement flexible tracing strategies in production.

## What You'll Learn:
- Enable/disable tracing at runtime without code changes
- Implement conditional tracing based on user attributes
- Create sampling strategies for high-throughput systems
- Measure and optimize tracing performance impact
- Control integration tracking dynamically

## Prerequisites:
```bash
pip install opik
```
"""

# %% [markdown]
"""
## Setup and Imports

First, let's import the necessary libraries and set up our environment.
"""

# %%

# Set up Opik for this session
print(f"Opik version: {opik.__version__}")
print(f"Initial tracing state: {opik.is_tracing_active()}")

# %% [markdown]
"""
## 1. Basic Runtime Control

The simplest use case is toggling tracing on and off during runtime.
"""

# %%
# Check current state
print(f"Current tracing state: {opik.is_tracing_active()}")

# Disable tracing globally
opik.set_tracing_active(False)
print(f"After disabling: {opik.is_tracing_active()}")

# Re-enable tracing
opik.set_tracing_active(True)
print(f"After enabling: {opik.is_tracing_active()}")

# %% [markdown]
"""
## 2. Context-Aware Tracing

Create a context manager for temporary tracing control.
"""


# %%
@contextmanager
def tracing_enabled(enabled: bool):
    """Context manager for temporary tracing control."""
    original_state = opik.is_tracing_active()
    try:
        opik.set_tracing_active(enabled)
        yield
    finally:
        opik.set_tracing_active(original_state)


# Example usage
print(f"Before context: {opik.is_tracing_active()}")

with tracing_enabled(False):
    print(f"Inside context (disabled): {opik.is_tracing_active()}")
    # Any traced functions here won't create spans

print(f"After context: {opik.is_tracing_active()}")

# %% [markdown]
"""
## 3. Function Decorators with Tracing

Let's create some functions to trace and see how dynamic control affects them.
"""


# %%
@opik.track(name="user_query_handler")
def handle_user_query(query: str, user_id: str, user_tier: str) -> Dict[str, Any]:
    """Simulate handling a user query with different processing based on tier."""
    processing_time = 0.1 if user_tier == "premium" else 0.05
    time.sleep(processing_time)

    return {
        "response": f"Processed query: {query}",
        "user_id": user_id,
        "tier": user_tier,
        "processing_time": processing_time,
    }


@opik.track(name="data_enrichment")
def enrich_data(data: Dict[str, Any]) -> Dict[str, Any]:
    """Simulate data enrichment process."""
    enriched = data.copy()
    enriched["enriched"] = True
    enriched["timestamp"] = time.time()
    enriched["score"] = random.uniform(0.7, 1.0)
    return enriched


# Test with tracing enabled
print("=== Testing with tracing ENABLED ===")
opik.set_tracing_active(True)
result = handle_user_query("What is machine learning?", "user123", "premium")
print(f"Result: {result}")

# Test with tracing disabled
print("\n=== Testing with tracing DISABLED ===")
opik.set_tracing_active(False)
result = handle_user_query("How does AI work?", "user456", "free")
print(f"Result: {result}")

# %% [markdown]
"""
## 4. Conditional Tracing Strategies

Implement different strategies for when to enable tracing.
"""


# %%
class TracingStrategy:
    """Base class for tracing strategies."""

    def should_trace(self, **kwargs) -> bool:
        """Determine if tracing should be enabled for this request."""
        raise NotImplementedError


class UserTierStrategy(TracingStrategy):
    """Trace only premium users."""

    def __init__(self, premium_tiers: List[str] = None):
        self.premium_tiers = premium_tiers or ["premium", "enterprise"]

    def should_trace(self, user_tier: str = None, **kwargs) -> bool:
        return user_tier in self.premium_tiers


class SamplingStrategy(TracingStrategy):
    """Trace a percentage of requests."""

    def __init__(self, sample_rate: float = 0.1):
        self.sample_rate = sample_rate

    def should_trace(self, **kwargs) -> bool:
        return random.random() < self.sample_rate


class DebugModeStrategy(TracingStrategy):
    """Trace when in debug mode or for specific users."""

    def __init__(self, debug_users: List[str] = None):
        self.debug_users = debug_users or []
        self.debug_mode = False

    def should_trace(self, user_id: str = None, **kwargs) -> bool:
        return self.debug_mode or (user_id in self.debug_users)

    def enable_debug(self):
        self.debug_mode = True

    def disable_debug(self):
        self.debug_mode = False


# Test different strategies
strategies = {
    "premium_only": UserTierStrategy(),
    "10_percent_sample": SamplingStrategy(0.1),
    "debug_mode": DebugModeStrategy(["debug_user_1", "debug_user_2"]),
}

# %% [markdown]
"""
## 5. Smart Request Handler

Create a request handler that uses tracing strategies.
"""


# %%
class SmartRequestHandler:
    """Request handler with configurable tracing strategy."""

    def __init__(self, strategy: TracingStrategy):
        self.strategy = strategy
        self.request_count = 0
        self.traced_count = 0

    def handle_request(
        self, query: str, user_id: str, user_tier: str
    ) -> Dict[str, Any]:
        """Handle request with conditional tracing."""
        self.request_count += 1

        # Determine if we should trace this request
        should_trace = self.strategy.should_trace(
            user_id=user_id, user_tier=user_tier, query=query
        )

        # Set tracing state
        opik.set_tracing_active(should_trace)

        if should_trace:
            self.traced_count += 1

        # Process the request (this will be traced if enabled)
        result = handle_user_query(query, user_id, user_tier)
        enriched = enrich_data(result)

        return {
            **enriched,
            "traced": should_trace,
            "request_number": self.request_count,
        }

    def get_stats(self) -> Dict[str, Any]:
        """Get handler statistics."""
        trace_rate = (
            (self.traced_count / self.request_count) if self.request_count > 0 else 0
        )
        return {
            "total_requests": self.request_count,
            "traced_requests": self.traced_count,
            "trace_rate": f"{trace_rate:.1%}",
        }


# Test with premium-only strategy
print("=== Testing Premium-Only Strategy ===")
handler = SmartRequestHandler(UserTierStrategy())

requests = [
    ("What is AI?", "user1", "free"),
    ("Explain ML", "user2", "premium"),
    ("How does it work?", "user3", "free"),
    ("Advanced question", "user4", "enterprise"),
]

for query, user_id, tier in requests:
    result = handler.handle_request(query, user_id, tier)
    print(f"User {user_id} ({tier}): Traced = {result['traced']}")

print(f"Strategy stats: {handler.get_stats()}")

# %% [markdown]
"""
## 6. Performance Impact Analysis

Measure the performance difference between traced and untraced execution.
"""


# %%
def performance_benchmark(func, iterations: int = 100) -> Dict[str, float]:
    """Benchmark function performance with and without tracing."""

    # Prepare test data
    test_data = {"items": list(range(100)), "metadata": {"test": True}}

    # Benchmark with tracing enabled
    opik.set_tracing_active(True)
    start_time = time.time()
    for _ in range(iterations):
        func(test_data)
    traced_time = time.time() - start_time

    # Benchmark with tracing disabled
    opik.set_tracing_active(False)
    start_time = time.time()
    for _ in range(iterations):
        func(test_data)
    untraced_time = time.time() - start_time

    # Calculate metrics
    overhead_pct = ((traced_time - untraced_time) / untraced_time) * 100

    return {
        "traced_time_ms": traced_time * 1000,
        "untraced_time_ms": untraced_time * 1000,
        "overhead_percentage": overhead_pct,
        "iterations": iterations,
    }


# Run benchmark
print("=== Performance Benchmark ===")
benchmark_results = performance_benchmark(enrich_data, iterations=200)

print(f"Traced execution: {benchmark_results['traced_time_ms']:.2f}ms")
print(f"Untraced execution: {benchmark_results['untraced_time_ms']:.2f}ms")
print(f"Overhead: {benchmark_results['overhead_percentage']:.1f}%")

# %% [markdown]
"""
## 7. Integration Control

Control when integrations apply their tracking.
"""


# %%
# Mock OpenAI client for demonstration
class MockOpenAIClient:
    """Mock OpenAI client for testing."""

    def __init__(self):
        self.base_url = type("BaseURL", (), {"host": "api.openai.com"})()
        self.__version__ = "1.0.0"
        self.chat = type(
            "Chat",
            (),
            {"completions": type("Completions", (), {"create": self._mock_create})()},
        )()
        self.beta = type(
            "Beta",
            (),
            {
                "chat": type(
                    "Chat",
                    (),
                    {
                        "completions": type(
                            "Completions",
                            (),
                            {"parse": self._mock_create, "stream": self._mock_create},
                        )()
                    },
                )()
            },
        )()
        self.responses = type("Responses", (), {"create": self._mock_create})()

    def _mock_create(self, **kwargs):
        return {"choices": [{"message": {"content": "Mock response"}}]}


def test_integration_control():
    """Test how integration tracking respects runtime flags."""

    print("=== Integration Control Test ===")

    # Create client
    client = MockOpenAIClient()

    # Test with tracing disabled - integration should be bypassed
    print("1. Setting up integration with tracing DISABLED")
    opik.set_tracing_active(False)
    tracked_client_1 = track_openai(client)
    has_tracking_1 = hasattr(tracked_client_1, "opik_tracked")
    print(f"   Client has tracking attribute: {has_tracking_1}")

    # Test with tracing enabled - integration should be applied
    print("2. Setting up integration with tracing ENABLED")
    opik.set_tracing_active(True)
    tracked_client_2 = track_openai(client)
    has_tracking_2 = hasattr(tracked_client_2, "opik_tracked")
    print(f"   Client has tracking attribute: {has_tracking_2}")

    return {
        "disabled_has_tracking": has_tracking_1,
        "enabled_has_tracking": has_tracking_2,
    }


integration_results = test_integration_control()

# %% [markdown]
"""
## 8. Production Usage Patterns

Real-world patterns for using dynamic tracing in production.
"""


# %%
class ProductionTracingManager:
    """Production-ready tracing manager with multiple strategies."""

    def __init__(self):
        self.strategies = {}
        self.current_strategy = None
        self.fallback_enabled = True

    def register_strategy(self, name: str, strategy: TracingStrategy):
        """Register a tracing strategy."""
        self.strategies[name] = strategy

    def set_strategy(self, name: str):
        """Set the active tracing strategy."""
        if name not in self.strategies:
            raise ValueError(f"Unknown strategy: {name}")
        self.current_strategy = name

    def should_trace(self, **context) -> bool:
        """Determine if current request should be traced."""
        if not self.current_strategy:
            return self.fallback_enabled

        try:
            strategy = self.strategies[self.current_strategy]
            return strategy.should_trace(**context)
        except Exception:
            # Fallback to enabled if strategy fails
            return self.fallback_enabled

    def configure_tracing(self, **context):
        """Configure tracing for current request."""
        should_trace = self.should_trace(**context)
        opik.set_tracing_active(should_trace)
        return should_trace


# Set up production manager
tracer = ProductionTracingManager()
tracer.register_strategy("premium_users", UserTierStrategy(["premium", "enterprise"]))
tracer.register_strategy("sampling_10pct", SamplingStrategy(0.1))
tracer.register_strategy("debug_users", DebugModeStrategy(["admin", "tester"]))


# Example: API endpoint handler
def api_endpoint_handler(request_data: Dict[str, Any]) -> Dict[str, Any]:
    """Simulate an API endpoint with smart tracing."""

    # Extract request context
    user_tier = request_data.get("user_tier", "free")
    user_id = request_data.get("user_id", "anonymous")
    endpoint = request_data.get("endpoint", "unknown")

    # Configure tracing based on current strategy
    traced = tracer.configure_tracing(
        user_tier=user_tier, user_id=user_id, endpoint=endpoint
    )

    # Process request (will be traced if enabled)
    result = handle_user_query(request_data.get("query", ""), user_id, user_tier)

    return {**result, "traced": traced, "strategy": tracer.current_strategy}


# Test different production scenarios
print("=== Production Scenarios ===")

scenarios = [
    {"strategy": "premium_users", "description": "Premium users only"},
    {"strategy": "sampling_10pct", "description": "10% sampling"},
    {"strategy": "debug_users", "description": "Debug users only"},
]

sample_requests = [
    {"user_id": "user1", "user_tier": "free", "query": "Basic question"},
    {"user_id": "user2", "user_tier": "premium", "query": "Advanced question"},
    {"user_id": "admin", "user_tier": "free", "query": "Debug query"},
]

for scenario in scenarios:
    print(f"\n--- Strategy: {scenario['description']} ---")
    tracer.set_strategy(scenario["strategy"])

    for request in sample_requests:
        result = api_endpoint_handler(request)
        print(
            f"User {request['user_id']} ({request['user_tier']}): "
            f"Traced = {result['traced']}"
        )

# %% [markdown]
"""
## 9. Configuration Management

Reset tracing to configuration defaults and environment variables.
"""

# %%
print("=== Configuration Management ===")

# Override tracing at runtime
opik.set_tracing_active(False)
print(f"Runtime override: {opik.is_tracing_active()}")

# Reset to configuration default
opik.reset_tracing_to_config_default()
print(f"After reset: {opik.is_tracing_active()}")
print("(Uses OPIK_TRACK_DISABLE environment variable or config file)")

# %% [markdown]
"""
## 10. Best Practices Summary

Key recommendations for using dynamic tracing in production:

### Performance Optimization
- Use sampling strategies for high-throughput systems
- Disable tracing for non-critical requests
- Monitor overhead and adjust strategies accordingly

### Debugging and Monitoring
- Enable full tracing for premium users or debug sessions
- Use conditional tracing based on error rates or specific conditions
- Implement fallback strategies for reliability

### Operational Flexibility
- Design strategies that can be changed without code deployment
- Use context managers for temporary tracing states
- Implement gradual rollout of tracing changes

### Code Organization
- Centralize tracing strategy logic
- Use dependency injection for strategy selection
- Keep tracing concerns separate from business logic
"""

# %%
print("=== Dynamic Tracing Cookbook Complete ===")
print("\nKey takeaways:")
print("✅ Zero-overhead tracing when disabled")
print("✅ Flexible strategies for different use cases")
print("✅ Runtime control without code changes")
print("✅ Production-ready patterns and best practices")
print("✅ Easy integration with existing instrumentation")

# Reset to enabled state for any subsequent code
opik.set_tracing_active(True)
print(f"\nFinal tracing state: {opik.is_tracing_active()}")
