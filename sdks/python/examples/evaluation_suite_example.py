"""
Example script demonstrating EvaluationSuite with per-item evaluators.

This script:
1. Creates an evaluation suite with 3 items
2. Each item has its own evaluator(s) stored in the dataset item content
3. Runs the evaluation suite
4. Analyzes the results

The evaluators are stored in the dataset item content under the __evaluators__ key
and are extracted from the downloaded dataset items during evaluation.

Usage:
    # With a running backend:
    python examples/evaluation_suite_example.py

    # Test mode (no backend required):
    python examples/evaluation_suite_example.py --test
"""

import argparse
import json
from unittest import mock
from typing import Any, Dict, List

from opik.evaluation.engine import engine
from opik.evaluation.suite_evaluators import LLMJudge


def my_llm_task(data: dict) -> dict:
    """
    Simulated LLM task that processes user input and returns a response.

    In a real scenario, this would call an actual LLM.
    """
    user_input: str = data.get("user_input", "")

    # Simulate different responses based on input
    if "refund" in user_input.lower():
        response = "To request a refund, please visit our refund portal at example.com/refunds. You'll need your order number and the email used for the purchase. Refunds are typically processed within 5-7 business days."
    elif "password" in user_input.lower():
        response = "To reset your password, click 'Forgot Password' on the login page. Enter your email address and we'll send you a reset link. The link expires in 24 hours for security reasons."
    elif "shipping" in user_input.lower():
        response = "Standard shipping takes 5-7 business days. Express shipping (2-3 days) is available for an additional $9.99. Free shipping is available on orders over $50."
    else:
        response = "I'm here to help! Could you please provide more details about your question?"

    return {
        "input": user_input,
        "output": response,
    }


def create_test_items() -> List[Dict[str, Any]]:
    """Create test items with per-item evaluators stored in content."""

    # Item 1: Refund question with politeness and accuracy evaluators
    # Using string assertions - names are auto-generated
    refund_evaluator = LLMJudge(
        name="refund_judge",
        assertions=[
            "Response provides clear steps for getting a refund",
            "Response is polite and professional",
        ],
    )

    # Item 2: Password reset with security evaluator
    password_evaluator = LLMJudge(
        name="password_judge",
        assertions=[
            "Response mentions security measures like link expiration",
            "Response provides clear actionable steps",
        ],
    )

    # Item 3: Shipping question with multiple evaluators
    shipping_evaluator = LLMJudge(
        name="shipping_judge",
        assertions=[
            "Response covers all shipping options available",
            "Response clearly states pricing for each option",
        ],
    )
    format_evaluator = LLMJudge(
        name="format_judge",
        assertions=[
            "Response is well-organized and easy to read",
        ],
    )

    items = [
        {
            "id": "item-refund-001",
            "user_input": "How do I get a refund for my order?",
            "context": {"user_tier": "premium"},
            engine.EVALUATORS_KEY: [refund_evaluator.to_config().model_dump()],
        },
        {
            "id": "item-password-002",
            "user_input": "I forgot my password, how do I reset it?",
            engine.EVALUATORS_KEY: [password_evaluator.to_config().model_dump()],
        },
        {
            "id": "item-shipping-003",
            "user_input": "What are your shipping options?",
            engine.EVALUATORS_KEY: [
                shipping_evaluator.to_config().model_dump(),
                format_evaluator.to_config().model_dump(),
            ],
        },
    ]

    return items


def run_test_mode():
    """Run in test mode without a backend - demonstrates the flow with mocks."""
    print("=" * 60)
    print("EVALUATION SUITE TEST MODE")
    print("=" * 60)
    print("\nThis demonstrates how per-item evaluators work:")
    print("1. Evaluators are stored in dataset item content under __evaluators__")
    print("2. During evaluation, evaluators are extracted from each item")
    print("3. Each item is scored using its own evaluators")
    print()

    # Create test items
    items = create_test_items()

    print(f"Created {len(items)} test items:")
    for i, item in enumerate(items):
        print(f"\n--- Item {i + 1}: {item['user_input'][:40]}... ---")

        # Run the task
        task_output = my_llm_task(item)
        print(f"Task output: {task_output['output'][:60]}...")

        # Extract evaluators from item content
        evaluators = engine._extract_item_evaluators(item)
        print(f"Extracted {len(evaluators)} evaluator(s):")

        for evaluator in evaluators:
            print(f"  - {evaluator.name}: {len(evaluator.assertions)} assertion(s)")

            # Mock the LLM response for scoring
            # Response format matches ScoreResult structure: name, value, reason, metadata
            mock_results = {
                "results": [
                    {
                        "name": assertion,
                        "value": True,  # Simulate all passing
                        "reason": f"The response satisfies: {assertion[:50]}",
                        "metadata": {"confidence": 0.95},
                    }
                    for assertion in evaluator.assertions
                ]
            }

            with mock.patch.object(
                evaluator._model,
                "generate_string",
                return_value=json.dumps(mock_results),
            ):
                scores = evaluator.score(
                    input=task_output["input"],
                    output=task_output["output"],
                )

            print("    Score results:")
            for score in scores:
                status = "PASS" if score.value else "FAIL"
                print(f"      * {score.name}: {status}")

    print("\n" + "=" * 60)
    print("TEST MODE COMPLETED SUCCESSFULLY!")
    print("=" * 60)
    print("\nTo run with a real backend, start the Opik backend and run:")
    print("  python examples/evaluation_suite_example.py")


def run_with_backend():
    """Run with a real backend."""
    import opik

    # Initialize Opik client
    client = opik.Opik()

    # Create an evaluation suite (no suite-level evaluators - all evaluators are per-item)
    print("Creating evaluation suite...")
    suite = client.create_evaluation_suite(
        name="Customer Support Tests",
        description="Tests for customer support chatbot responses",
    )

    # Create evaluators using string assertions (names auto-generated)
    refund_evaluator = LLMJudge(
        name="refund_judge",
        assertions=[
            "Response provides clear steps for getting a refund",
            "Response is polite and professional",
        ],
    )

    password_evaluator = LLMJudge(
        name="password_judge",
        assertions=[
            "Response mentions security measures like link expiration",
            "Response provides clear actionable steps",
        ],
    )

    shipping_evaluator = LLMJudge(
        name="shipping_judge",
        assertions=[
            "Response covers all shipping options available",
            "Response clearly states pricing for each option",
        ],
    )

    # Add items with per-item evaluators
    print("Adding item 1: Refund question...")
    suite.add_item(
        data={
            "user_input": "How do I get a refund for my order?",
            "context": {"user_tier": "premium"},
        },
        evaluators=[refund_evaluator],
    )

    print("Adding item 2: Password reset question...")
    suite.add_item(
        data={
            "user_input": "I forgot my password, how do I reset it?",
        },
        evaluators=[password_evaluator],
    )

    print("Adding item 3: Shipping question...")
    suite.add_item(
        data={
            "user_input": "What are your shipping options?",
        },
        evaluators=[shipping_evaluator],
    )

    # Run the evaluation suite
    print("\nRunning evaluation suite...")
    print("=" * 60)

    results = suite.run(
        task=my_llm_task,
        experiment_name="evaluation_suite_test",
        verbose=2,
    )

    # Analyze results - EvaluationSuiteResult provides pass/fail status
    print("\n" + "=" * 60)
    print("EVALUATION SUITE RESULTS")
    print("=" * 60)

    # Suite-level pass/fail status
    suite_status = "PASSED" if results.passed else "FAILED"
    print(f"\nSuite Status: {suite_status}")
    print(f"Items Passed: {results.items_passed}/{results.items_total}")

    print(f"\nExperiment ID: {results.experiment_id}")
    print(f"Experiment Name: {results.experiment_name}")
    print(f"Experiment URL: {results.experiment_url}")

    # Per-item results
    print("\n" + "-" * 40)
    print("ITEM RESULTS")
    print("-" * 40)

    for item_id, item_result in results.item_results.items():
        item_status = "PASSED" if item_result.passed else "FAILED"
        print(f"\n--- Item: {item_id[:20]}... ---")
        print(f"Status: {item_status}")
        print(f"Runs Passed: {item_result.runs_passed}/{item_result.runs_total}")
        print(f"Pass Threshold: {item_result.pass_threshold}")

        for test_result in item_result.test_results:
            content = test_result.test_case.dataset_item_content

            if engine.EVALUATORS_KEY in content:
                print(
                    f"  Evaluators stored in item: {len(content[engine.EVALUATORS_KEY])} config(s)"
                )

            print(f"  Score Results ({len(test_result.score_results)}):")
            for score in test_result.score_results:
                status = "PASS" if score.value else "FAIL"
                print(f"    - {score.name}: {status}")

    print("\n" + "=" * 60)
    print("Done!")


def main():
    parser = argparse.ArgumentParser(description="Evaluation Suite Example")
    parser.add_argument(
        "--test",
        action="store_true",
        help="Run in test mode without a backend",
    )
    args = parser.parse_args()

    if args.test:
        run_test_mode()
    else:
        run_with_backend()


if __name__ == "__main__":
    main()
