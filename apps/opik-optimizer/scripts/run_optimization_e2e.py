#!/usr/bin/env python
"""
End-to-end test script for the optimization framework.

Creates an evaluation suite with LLMJudge assertions (including item-level
overrides), then runs a real optimization against a local Opik backend so
you can see the results in the Optimization Studio UI.

Prerequisites:
  - Local Opik backend running (http://localhost:8080)
  - OPENAI_API_KEY set in environment (or another LLM provider supported by litellm)
  - pip install -e apps/opik-optimizer  (the framework package)
  - pip install -e sdks/python          (the Opik SDK)

Usage:
  export OPENAI_API_KEY=sk-...
  python apps/opik-optimizer/scripts/run_optimization_e2e.py
"""

import logging
import os
import sys
import time

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("e2e-test")

# -- Configuration ----------------------------------------------------------

OPIK_URL = os.environ.get("OPIK_URL_OVERRIDE")  # None = use SDK default (cloud)
OPIK_WORKSPACE = os.environ.get("OPIK_WORKSPACE", "default")
OPIK_API_KEY = os.environ.get("OPIK_API_KEY")

SUITE_NAME = f"customer-support-regression-tests-{int(time.time())}"
OPTIMIZATION_NAME = "e2e-framework-test"
OBJECTIVE_NAME = "pass_rate"

# The model litellm will call for the optimization task.
MODEL = os.environ.get("OPIK_TEST_MODEL", "gpt-4o-mini")

PROMPT_MESSAGES = [
    {
        "role": "system",
        "content": (
            "You are a helpful customer support agent for an e-commerce company. "
            "Be professional, empathetic, and provide clear, actionable responses. "
            "If you don't know something, be honest about it."
        ),
    },
    {
        "role": "user",
        "content": "Customer question: {question}\nAdditional context: {context}",
    },
]

# ---------------------------------------------------------------------------


def _update_optimization_status(client, optimization_id, status):
    """Update optimization status via the SDK's REST client."""
    client.rest_client.optimizations.update_optimizations_by_id(
        optimization_id, status=status,
    )


def main():
    import opik
    from opik.evaluation.suite_evaluators import LLMJudge
    from opik_optimizer_framework import OptimizationContext, run_optimization

    # 1. Connect to Opik
    if OPIK_URL:
        os.environ["OPIK_URL_OVERRIDE"] = OPIK_URL
    logger.info("Connecting to Opik (workspace: %s, url: %s)", OPIK_WORKSPACE, OPIK_URL or "cloud default")

    client = opik.Opik(workspace=OPIK_WORKSPACE, api_key=OPIK_API_KEY)

    # 2. Create evaluation suite — exact dataset from the example script
    logger.info("Creating evaluation suite '%s'", SUITE_NAME)
    suite = client.create_evaluation_suite(
        name=SUITE_NAME,
        description="Regression tests for customer support agent responses",
        evaluators=[
            LLMJudge(
                assertions=[
                    "Response is relevant to the user question",
                ]
            )
        ],
        execution_policy={"runs_per_item": 1, "pass_threshold": 1},
    )

    # Test case 1: Refund request
    # No item-level evaluators — suite-level evaluators are used
    suite.add_item(
        data={
            "question": "I received a damaged product. How can I get a refund?",
            "context": "Order #12345, placed 3 days ago",
        },
    )

    # Test case 2: Shipping inquiry
    # No item-level evaluators — suite-level evaluators are used
    suite.add_item(
        data={
            "question": "Where is my package? It was supposed to arrive yesterday.",
            "context": "Tracking number: TRK789456",
        },
    )

    # Test case 3: Account security (CRITICAL)
    # Item-level evaluators OVERRIDE suite-level ones for this item
    # Also uses a stricter execution policy (5 runs, 4 must pass)
    suite.add_item(
        data={
            "question": "I think someone hacked my account. I see orders I didn't make!",
            "context": "Customer reports unauthorized activity",
        },
        evaluators=[
            LLMJudge(
                assertions=[
                    "The response treats the security concern with appropriate urgency",
                    "The response advises immediate steps to secure the account",
                    "The response mentions that unauthorized orders will be investigated",
                ]
            )
        ],
        execution_policy={"runs_per_item": 5, "pass_threshold": 4},
    )

    # Test case 4: Product question
    # No item-level evaluators — suite-level evaluators are used
    suite.add_item(
        data={
            "question": "Is the XYZ Wireless Headphones compatible with iPhone 15?",
            "context": "Product SKU: WH-2024-BLK",
        },
    )

    # Test case 5: Subscription cancellation
    # No item-level evaluators — suite-level evaluators are used
    suite.add_item(
        data={
            "question": "I want to cancel my premium subscription. This is too expensive.",
            "context": "Customer has been subscribed for 6 months",
        },
    )

    # Get dataset item IDs from the underlying dataset
    dataset_items = suite.dataset.get_items()
    dataset_item_ids = [str(item["id"]) for item in dataset_items]
    logger.info("Suite has %d items", len(dataset_item_ids))

    # 3. Create the optimization record (status is set to "running" automatically)
    logger.info("Creating optimization record")
    optimizer_type = "GepaOptimizer"
    optimization = client.create_optimization(
        dataset_name=SUITE_NAME,
        objective_name=OBJECTIVE_NAME,
        name=OPTIMIZATION_NAME,
        metadata={"optimizer": optimizer_type},
    )
    optimization_id = optimization.id
    logger.info("Optimization created: %s", optimization_id)

    # 4. Run the framework — evaluators come from the suite itself
    optimizer_parameters = {
        "max_candidates": 4,
        "reflection_minibatch_size": 2,
        "candidate_selection_strategy": "pareto",
        "seed": 42,
    }

    context = OptimizationContext(
        optimization_id=optimization_id,
        dataset_name=SUITE_NAME,
        prompt_messages=PROMPT_MESSAGES,
        model=MODEL,
        model_parameters={"temperature": 0.7, "max_tokens": 256},
        metric_type=OBJECTIVE_NAME,
        metric_parameters={},
        optimizer_type=optimizer_type,
        optimizer_parameters=optimizer_parameters,
    )

    logger.info("Starting optimization (optimizer_type=%s, model=%s)", optimizer_type, MODEL)
    try:
        result = run_optimization(
            context=context,
            client=client,
            dataset_item_ids=dataset_item_ids,
        )
        _update_optimization_status(client, optimization_id, "completed")
    except Exception:
        logger.exception("Optimization failed")
        _update_optimization_status(client, optimization_id, "error")
        client.end()
        sys.exit(1)

    # 5. Print results
    print("\n" + "=" * 60)
    print("OPTIMIZATION COMPLETE")
    print("=" * 60)
    print(f"  Optimization ID : {optimization_id}")
    print(f"  Final score     : {result.score:.4f}")
    print(f"  Initial score   : {result.initial_score}")
    print(f"  Total trials    : {len(result.all_trials)}")

    if result.best_trial:
        print(f"\n  Best trial:")
        print(f"    Score         : {result.best_trial.score:.4f}")
        print(f"    Experiment    : {result.best_trial.experiment_name}")
        print(f"    Prompt        :")
        for msg in result.best_trial.prompt_messages:
            print(f"      [{msg['role']}] {msg['content'][:80]}...")

    print(f"\n  Optimization trajectory:")
    for trial in result.all_trials:
        parents = trial.parent_candidate_ids or []
        print(
            f"    step={trial.step_index:2d}  score={trial.score:.4f}  "
            f"candidate={trial.candidate_id[:8]}  parents={[p[:8] for p in parents]}"
        )

    print(f"\n  View in UI: {OPIK_URL or 'https://www.comet.com/opik'}")
    print("=" * 60)

    client.end()


if __name__ == "__main__":
    if not os.environ.get("OPENAI_API_KEY") and "gpt" in MODEL.lower():
        print("ERROR: OPENAI_API_KEY not set. Export it or set OPIK_TEST_MODEL to another provider.")
        sys.exit(1)
    main()
