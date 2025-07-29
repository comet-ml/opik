#!/usr/bin/env python3
"""
Trajectory Accuracy Evaluation Example

This example demonstrates how to use Opik's TrajectoryAccuracy metric
to evaluate ReAct-style agent trajectories within the evaluation framework.
"""

from typing import Dict, Any
from opik.evaluation.metrics import TrajectoryAccuracy
from opik.evaluation import evaluate
from opik import Opik, track
import json


def create_trajectory_dataset():
    """Create a dataset with ReAct-style trajectories for evaluation."""

    client = Opik()
    dataset = client.get_or_create_dataset(
        name="trajectory_evaluation_dataset",
        description="Dataset for evaluating ReAct-style agent trajectories",
    )

    # Sample trajectory data
    trajectory_data = [
        {
            "trajectory_input": {
                "goal": "Find the weather in Paris",
                "trajectory": [
                    {
                        "thought": "I need to search for weather information in Paris",
                        "action": "search_weather(location='Paris')",
                        "observation": "Found weather data for Paris: 22°C, sunny",
                    },
                    {
                        "thought": "I have the weather data, now I should summarize it",
                        "action": "summarize_result()",
                        "observation": "Summary created: The weather in Paris is 22°C and sunny",
                    },
                ],
                "final_result": "The weather in Paris is 22°C and sunny",
            }
        },
        {
            "trajectory_input": {
                "goal": "Calculate the sum of 15 and 27",
                "trajectory": [
                    {
                        "thought": "I need to add 15 and 27 together",
                        "action": "calculate(15 + 27)",
                        "observation": "Result: 42",
                    }
                ],
                "final_result": "The sum of 15 and 27 is 42",
            }
        },
        {
            "trajectory_input": {
                "goal": "Find the capital of France",
                "trajectory": [
                    {
                        "thought": "I need to find France's capital",
                        "action": "search('weather in France')",  # Poor action choice
                        "observation": "Found weather information for various French cities",
                    },
                    {
                        "thought": "This doesn't help, let me try something else",
                        "action": "search('French cuisine')",  # Still poor choice
                        "observation": "Found information about French food",
                    },
                ],
                "final_result": "Paris is the capital of France",  # Result doesn't match trajectory
            }
        },
        {
            "trajectory_input": {
                "goal": "Research the population of Tokyo",
                "trajectory": [
                    {
                        "thought": "I need to search for Tokyo population data",
                        "action": "search('Tokyo population 2024')",
                        "observation": "Tokyo has approximately 14 million people in the city, 37 million in metro area",
                    },
                    {
                        "thought": "I found the data, let me verify with another source",
                        "action": "search('Tokyo metropolitan area population')",
                        "observation": "Confirmed: Tokyo metro area has about 37-38 million residents",
                    },
                    {
                        "thought": "Now I should summarize this information clearly",
                        "action": "summarize_findings()",
                        "observation": "Summary prepared with population figures",
                    },
                ],
                "final_result": "Tokyo city has about 14 million people, while the greater Tokyo metropolitan area has approximately 37-38 million residents, making it the world's largest urban agglomeration.",
            }
        },
    ]

    # Insert data into dataset
    dataset.insert_from_json(
        json_array=json.dumps(trajectory_data),
        keys_mapping={"trajectory_input": "input"},
    )

    return dataset


@track()
def trajectory_evaluation_task(item: Dict[str, Any]) -> Dict[str, Any]:
    """
    Task that simulates evaluating an agent trajectory.
    In practice, this would be where your agent generates the trajectory.
    """
    # Extract the trajectory components
    trajectory_data = item["input"]

    # For this example, we're just passing through the pre-made trajectory
    # In a real scenario, this is where your agent would generate the trajectory
    return {
        "goal": trajectory_data["goal"],
        "trajectory": trajectory_data["trajectory"],
        "final_result": trajectory_data["final_result"],
        "metadata": {
            "trajectory_steps": len(trajectory_data["trajectory"]),
            "evaluation_type": "react_agent_trajectory",
        },
    }


def main():
    """Run the trajectory accuracy evaluation example."""

    print("🚀 Starting Trajectory Accuracy Evaluation with Opik")
    print("=" * 60)

    # Create dataset
    print("📊 Creating trajectory dataset...")
    dataset = create_trajectory_dataset()
    print(f"✅ Dataset '{dataset.name}' created with trajectory examples")

    # Create trajectory accuracy metric
    trajectory_metric = TrajectoryAccuracy(
        name="trajectory_accuracy_evaluation", track=True
    )

    print("\n🎯 Running evaluation...")

    # Run evaluation
    evaluation_result = evaluate(
        experiment_name="trajectory_accuracy_experiment",
        dataset=dataset,
        task=trajectory_evaluation_task,
        scoring_metrics=[trajectory_metric],
        experiment_config={
            "model": "gpt-4o-mini",  # Following user rules
            "evaluation_type": "react_agent_trajectory",
            "metric": "trajectory_accuracy",
        },
    )

    print("\n✅ Evaluation completed!")
    print(f"📊 Experiment: {evaluation_result.experiment_name}")
    print("📈 Results available in Opik dashboard")

    # Display summary
    print("\n📋 Summary:")
    print(f"   Total test cases: {len(evaluation_result.test_results)}")
    print("   Metric used: TrajectoryAccuracy")
    print(
        "   Evaluation assesses: reasoning quality, action appropriateness, goal achievement"
    )

    return evaluation_result


if __name__ == "__main__":
    try:
        result = main()
        print("\n🎉 Trajectory Accuracy evaluation completed successfully!")
        print("📊 View detailed results in your Opik dashboard")
    except Exception as e:
        print(f"\n❌ Evaluation failed: {e}")
        print("💡 Make sure you have:")
        print("   - OPENAI_API_KEY set in environment")
        print("   - Opik properly configured")
        print("   - Network connectivity for LLM calls")
