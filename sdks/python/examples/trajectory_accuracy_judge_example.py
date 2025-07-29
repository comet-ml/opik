#!/usr/bin/env python3
"""
Example script for the TrajectoryAccuracy metric.

This script demonstrates how to use the TrajectoryAccuracy metric
with sample ReAct-style agent trajectories.
"""

import sys
import os

from opik.evaluation.metrics import TrajectoryAccuracy

# Add the parent directory to the Python path to ensure the 'opik' module can be found.
sys.path.insert(
    0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
)


def run_basic_example(metric: TrajectoryAccuracy):
    """Demonstrates the TrajectoryAccuracy metric with a basic example."""
    print("Running TrajectoryAccuracy with a basic example...")
    print("=" * 60)

    example = {
        "goal": "Find the weather in Paris",
        "trajectory": [
            {
                "thought": "I need to search for weather information in Paris",
                "action": "search_weather(location='Paris')",
                "observation": "Found weather data for Paris: 22°C, sunny",
            },
            {
                "thought": "I found the weather, now summarizing",
                "action": "summarize_weather()",
                "observation": "The weather in Paris is 22°C and sunny",
            },
        ],
        "final_result": "The weather in Paris is 22°C and sunny",
    }

    try:
        result = metric.score(**example)

        print("INPUT:")
        print(f"Goal: {example['goal']}")
        print(f"Number of trajectory steps: {len(example['trajectory'])}")
        print(f"Final result: {example['final_result']}")
        print()

        print("OUTPUT:")
        print(f"Score: {result.value}")
        print(f"Explanation: {result.reason}")
        print()

        # Validate result format
        assert isinstance(result.value, float), "Score should be a float"
        assert (
            0.0 <= result.value <= 1.0
        ), f"Score {result.value} should be between 0.0 and 1.0"
        assert isinstance(result.reason, str), "Explanation should be a string"
        assert len(result.reason) > 0, "Explanation should not be empty"

        print("✅ Example completed successfully!")
        return True

    except Exception as e:
        print(f"❌ Example failed with error: {e}")
        return False


def run_edge_cases_example(metric: TrajectoryAccuracy):
    """Demonstrates the TrajectoryAccuracy metric with various edge cases."""
    print("\nRunning edge cases...")
    print("=" * 60)

    test_cases = [
        {
            "name": "Empty trajectory",
            "example": {
                "goal": "Do something",
                "trajectory": [],
                "final_result": "Nothing was done",
            },
        },
        {
            "name": "Missing goal",
            "example": {
                "goal": "",
                "trajectory": [
                    {
                        "thought": "I need to do something",
                        "action": "do_action()",
                        "observation": "Action completed",
                    }
                ],
                "final_result": "Task completed",
            },
        },
        {
            "name": "Incomplete trajectory step",
            "example": {
                "goal": "Find information",
                "trajectory": [
                    {
                        "thought": "I need to search",
                    }
                ],
                "final_result": "Search completed",
            },
        },
    ]

    passed_count = 0
    for case in test_cases:
        print(f"\nRunning case: {case['name']}")
        try:
            result = metric.score(**case["example"])
            print(f"  Score: {result.value}")
            print(f"  Explanation: {result.reason[:100]}...")

            # Basic validation
            assert isinstance(result.value, float)
            assert 0.0 <= result.value <= 1.0
            assert isinstance(result.reason, str)

            print("  ✅ Passed")
            passed_count += 1

        except Exception as e:
            print(f"  ❌ Failed: {e}")

    print(f"\nEdge case examples: {passed_count}/{len(test_cases)} completed")
    return passed_count == len(test_cases)


def run_complex_trajectory_example(metric: TrajectoryAccuracy):
    """Demonstrates the metric with a more complex multi-step trajectory."""
    print("\nRunning complex trajectory example...")
    print("=" * 60)

    example = {
        "goal": "Research and summarize the population of the top 3 largest cities in France",
        "trajectory": [
            {
                "thought": "I need to find information about the largest cities in France first",
                "action": "search(query='largest cities in France')",
                "observation": "Found that Paris, Marseille, and Lyon are the top 3 largest cities",
            },
            {
                "thought": "Now I need to get population data for Paris",
                "action": "search(query='Paris France population 2024')",
                "observation": "Paris population is approximately 2.16 million",
            },
            {
                "thought": "Next, I need population data for Marseille",
                "action": "search(query='Marseille France population 2024')",
                "observation": "Marseille population is approximately 870,000",
            },
            {
                "thought": "Finally, I need population data for Lyon",
                "action": "search(query='Lyon France population 2024')",
                "observation": "Lyon population is approximately 520,000",
            },
            {
                "thought": "Now I have all the data, I should summarize it",
                "action": "summarize(data='Paris: 2.16M, Marseille: 870K, Lyon: 520K')",
                "observation": "Summary created with population data for top 3 French cities",
            },
        ],
        "final_result": "The top 3 largest cities in France by population are: 1) Paris (2.16 million), 2) Marseille (870,000), 3) Lyon (520,000)",
    }

    try:
        result = metric.score(**example)

        print("COMPLEX TRAJECTORY EXAMPLE:")
        print(f"Goal: {example['goal']}")
        print(f"Steps: {len(example['trajectory'])}")
        print(f"Score: {result.value}")
        print(f"Explanation: {result.reason}")

        assert isinstance(result.value, float)
        assert 0.0 <= result.value <= 1.0
        assert isinstance(result.reason, str)

        print("✅ Complex trajectory example completed!")
        return True

    except Exception as e:
        print(f"❌ Complex trajectory example failed: {e}")
        return False


if __name__ == "__main__":
    print("Trajectory Accuracy Metric Example Suite")
    print("=" * 60)

    # Instantiate the metric
    trajectory_metric = TrajectoryAccuracy()

    # Run all examples
    success_count = 0
    total_examples = 3

    if run_basic_example(trajectory_metric):
        success_count += 1

    if run_edge_cases_example(trajectory_metric):
        success_count += 1

    if run_complex_trajectory_example(trajectory_metric):
        success_count += 1

    print("\n" + "=" * 60)
    print(f"FINAL RESULTS: {success_count}/{total_examples} example suites ran")

    if success_count == total_examples:
        print("🎉 All examples ran successfully!")
        sys.exit(0)
    else:
        print("⚠️  Some examples failed. Please check the output above.")
        sys.exit(1)
