#!/usr/bin/env python
"""
End-to-end test for GepaV2Optimizer with 10 evaluation suite items.

Includes items designed to be hard to pass so the optimizer has room to
improve through prompt evolution. The suite uses LLMJudge assertions with
varying difficulty levels.

Prerequisites:
  - Local Opik backend running (http://localhost:8080)
  - OPENAI_API_KEY set in environment (or another provider via OPIK_TEST_MODEL)
  - pip install -e apps/opik-optimizer
  - pip install -e sdks/python

Usage:
  export OPENAI_API_KEY=sk-...
  python apps/opik-optimizer/scripts/run_gepa_v2_e2e.py
"""

import logging
import os
import sys
import time

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("gepa-v2-e2e")

# -- Configuration ----------------------------------------------------------

OPIK_URL = os.environ.get("OPIK_URL_OVERRIDE")
OPIK_WORKSPACE = os.environ.get("OPIK_WORKSPACE", "default")
OPIK_API_KEY = os.environ.get("OPIK_API_KEY")

SUITE_NAME = f"gepa-v2-customer-support-{int(time.time())}"
OPTIMIZATION_NAME = "gepa-v2-e2e"
OBJECTIVE_NAME = "pass_rate"

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


def _update_optimization_status(client, optimization_id, status):
    client.rest_client.optimizations.update_optimizations_by_id(
        optimization_id, status=status,
    )


def _build_suite(client):
    from opik.evaluation.suite_evaluators import LLMJudge

    suite = client.create_evaluation_suite(
        name=SUITE_NAME,
        description="Customer support regression tests (10 items, mixed difficulty)",
        evaluators=[
            LLMJudge(
                assertions=[
                    "Response is relevant to the user question",
                ]
            )
        ],
        execution_policy={"runs_per_item": 1, "pass_threshold": 1},
    )

    # -- Easy items (likely to pass with baseline prompt) ---------------------

    suite.add_item(
        data={
            "question": "What are your store hours?",
            "context": "General inquiry",
        },
    )

    suite.add_item(
        data={
            "question": "I received a damaged product. How can I get a refund?",
            "context": "Order #12345, placed 3 days ago",
        },
    )

    suite.add_item(
        data={
            "question": "Where is my package? It was supposed to arrive yesterday.",
            "context": "Tracking number: TRK789456",
        },
    )

    suite.add_item(
        data={
            "question": "Do you ship internationally?",
            "context": "Customer located in Germany",
        },
    )

    # -- Medium items (might pass, might not) --------------------------------

    suite.add_item(
        data={
            "question": "Is the XYZ Wireless Headphones compatible with iPhone 15?",
            "context": "Product SKU: WH-2024-BLK",
        },
        evaluators=[
            LLMJudge(
                assertions=[
                    "Response is relevant to the product compatibility question",
                    "Response acknowledges that the agent may not have specific product specs",
                ]
            )
        ],
    )

    suite.add_item(
        data={
            "question": "I want to cancel my premium subscription. This is too expensive.",
            "context": "Customer has been subscribed for 6 months, pays $29.99/month",
        },
        evaluators=[
            LLMJudge(
                assertions=[
                    "Response acknowledges the customer's cost concern",
                    "Response provides clear cancellation steps or offers alternatives",
                ]
            )
        ],
    )

    # -- Hard items (designed to be difficult for a generic prompt) -----------

    suite.add_item(
        data={
            "question": "I think someone hacked my account. I see orders I didn't make!",
            "context": "Customer reports unauthorized activity, 3 unknown orders in last 24h",
        },
        evaluators=[
            LLMJudge(
                assertions=[
                    "Response treats the security concern with appropriate urgency",
                    "Response advises immediate steps to secure the account (e.g., change password)",
                    "Response mentions that unauthorized orders will be investigated or reversed",
                ]
            )
        ],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )

    suite.add_item(
        data={
            "question": (
                "I ordered a laptop 2 weeks ago, it arrived broken, I called 3 times "
                "and each time was told someone would call back but nobody did. "
                "I am extremely frustrated and want a full refund PLUS compensation "
                "for the terrible service."
            ),
            "context": "Order #98765, 3 previous support tickets closed without resolution",
        },
        evaluators=[
            LLMJudge(
                assertions=[
                    "Response sincerely apologizes for the repeated failures in service",
                    "Response acknowledges the specific frustration of 3 unreturned callbacks",
                    "Response offers a concrete resolution path (refund, escalation, or compensation)",
                    "Response does not make promises the agent cannot guarantee",
                ]
            )
        ],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )

    suite.add_item(
        data={
            "question": (
                "I bought a gift card for my mom's birthday but the code doesn't work. "
                "Her birthday is TODAY and I can't give her anything else. Please help!"
            ),
            "context": "Gift card purchased yesterday, $100 value, code: GC-INVALID-404",
        },
        evaluators=[
            LLMJudge(
                assertions=[
                    "Response shows empathy for the time-sensitive birthday situation",
                    "Response provides an immediate workaround or expedited resolution",
                    "Response offers to verify or replace the gift card code",
                ]
            )
        ],
    )

    suite.add_item(
        data={
            "question": (
                "I'm a loyal customer for 5 years. I just noticed you raised prices "
                "on all items by 20% with no warning. I feel betrayed. Why should I "
                "stay when competitors offer better deals?"
            ),
            "context": "Customer with $15,000 lifetime spend, Gold tier loyalty member",
        },
        evaluators=[
            LLMJudge(
                assertions=[
                    "Response acknowledges the customer's loyalty and long-term relationship",
                    "Response addresses the pricing concern directly without being dismissive",
                    "Response highlights value propositions or loyalty benefits specific to this customer",
                    "Response does not lie about or deny the price increase",
                ]
            )
        ],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )

    return suite


def main():
    import opik
    from opik_optimizer_framework import OptimizationContext, run_optimization

    if OPIK_URL:
        os.environ["OPIK_URL_OVERRIDE"] = OPIK_URL
    logger.info("Connecting to Opik (workspace: %s, url: %s)", OPIK_WORKSPACE, OPIK_URL or "cloud default")

    client = opik.Opik(workspace=OPIK_WORKSPACE, api_key=OPIK_API_KEY)

    logger.info("Creating evaluation suite '%s' with 10 items", SUITE_NAME)
    suite = _build_suite(client)

    dataset_items = list(suite.dataset.get_items())
    logger.info("Suite has %d items", len(dataset_items))

    logger.info("Creating optimization record")
    optimizer_type = "GepaV2Optimizer"
    optimization = client.create_optimization(
        dataset_name=SUITE_NAME,
        objective_name=OBJECTIVE_NAME,
        name=OPTIMIZATION_NAME,
        metadata={"optimizer": optimizer_type},
    )
    optimization_id = optimization.id
    logger.info("Optimization created: %s", optimization_id)

    optimizer_parameters = {
        "max_candidates": 5,
        "reflection_minibatch_size": 3,
        "candidate_selection_strategy": "pareto",
        "seed": 42,
    }

    context = OptimizationContext(
        optimization_id=optimization_id,
        dataset_name=SUITE_NAME,
        prompt_messages=[],
        model=MODEL,
        model_parameters={},
        metric_type=OBJECTIVE_NAME,
        metric_parameters={},
        optimizer_type=optimizer_type,
        optimizer_parameters=optimizer_parameters,
        optimizable_keys=["system_prompt", "user_message"],
        baseline_config={
            "system_prompt": PROMPT_MESSAGES[0]["content"],
            "user_message": PROMPT_MESSAGES[1]["content"],
            "model": MODEL,
            "model_parameters": {"temperature": 0.7, "max_tokens": 512},
        },
    )

    logger.info("Starting optimization (optimizer_type=%s, model=%s)", optimizer_type, MODEL)
    try:
        result = run_optimization(
            context=context,
            client=client,
            dataset_items=dataset_items,
        )
        _update_optimization_status(client, optimization_id, "completed")
    except Exception:
        logger.exception("Optimization failed")
        _update_optimization_status(client, optimization_id, "error")
        client.end()
        sys.exit(1)

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
        config = result.best_trial.config
        for key in ("system_prompt", "user_message"):
            if key in config:
                print(f"      {key}: {str(config[key])[:100]}...")

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
