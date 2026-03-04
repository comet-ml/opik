#!/usr/bin/env python
"""Quick GEPA v2 test with 5 hard items and 2 candidates for fast iteration."""

import logging
import os
import sys
import time
import opik

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("gepa-v2-quick")

OPIK_URL = os.environ.get("OPIK_URL_OVERRIDE")
OPIK_WORKSPACE = os.environ.get("OPIK_WORKSPACE", "default")
OPIK_API_KEY = os.environ.get("OPIK_API_KEY")

SUITE_NAME = f"gepa-v2-quick-{int(time.time())}"
OPTIMIZATION_NAME = "gepa-v2-quick"
OBJECTIVE_NAME = "pass_rate"
MODEL = os.environ.get("OPIK_TEST_MODEL", "gpt-4o-mini")

PROMPT_MESSAGES = [
    {
        "role": "system",
        "content": "You are a customer support agent. Answer questions briefly.",
    },
    {
        "role": "user",
        "content": "{question}",
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
        description="Quick GEPA v2 test (5 hard items)",
        evaluators=[
            LLMJudge(assertions=["Response is relevant to the user question"])
        ],
        execution_policy={"runs_per_item": 1, "pass_threshold": 1},
    )

    suite.add_item(
        data={
            "question": "I think someone hacked my account! I see 3 orders I didn't make in the last 24 hours!",
            "context": "3 unknown orders in last 24h, customer account flagged",
        },
        evaluators=[LLMJudge(assertions=[
            "Response treats the security concern with appropriate urgency",
            "Response advises immediate steps to secure the account such as changing password",
            "Response mentions that unauthorized orders will be investigated or reversed",
        ])],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )
    suite.add_item(
        data={
            "question": (
                "I ordered a laptop 2 weeks ago, it arrived broken, I called 3 times "
                "and each time was told someone would call back but nobody did. "
                "I am EXTREMELY frustrated and want a FULL refund PLUS compensation!"
            ),
            "context": "Order #98765, 3 previous support tickets closed without resolution",
        },
        evaluators=[LLMJudge(assertions=[
            "Response sincerely apologizes for the repeated failures in service",
            "Response acknowledges the specific frustration of 3 unreturned callbacks",
            "Response offers a concrete resolution path like refund, escalation, or compensation",
            "Response does not make promises the agent cannot guarantee",
        ])],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )
    suite.add_item(
        data={
            "question": (
                "Gift card code doesn't work and my mom's birthday is TODAY! "
                "I can't give her anything else. Please help URGENTLY!"
            ),
            "context": "Gift card purchased yesterday, $100 value, code: GC-INVALID-404",
        },
        evaluators=[LLMJudge(assertions=[
            "Response shows empathy for the time-sensitive birthday situation",
            "Response provides an immediate workaround or expedited resolution",
            "Response offers to verify or replace the gift card code",
        ])],
    )
    suite.add_item(
        data={
            "question": (
                "I'm a loyal customer for 5 years with $15,000 spent. You raised prices "
                "20% with no warning. I feel betrayed. Why should I stay when competitors "
                "offer better deals?"
            ),
            "context": "Gold tier loyalty member, 5 year history, $15K lifetime spend",
        },
        evaluators=[LLMJudge(assertions=[
            "Response acknowledges the customer's loyalty and long-term relationship",
            "Response addresses the pricing concern directly without being dismissive",
            "Response highlights value propositions or loyalty benefits specific to this customer",
            "Response does not lie about or deny the price increase",
        ])],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )
    suite.add_item(
        data={
            "question": (
                "I bought a wireless headphone that was advertised as 'noise canceling' "
                "but it barely blocks any noise. This is false advertising! I want my money back!"
            ),
            "context": "Product: WH-2024-BLK, purchased 5 days ago, 30-day return policy",
        },
        evaluators=[LLMJudge(assertions=[
            "Response acknowledges the customer's frustration about product not meeting expectations",
            "Response addresses the false advertising concern seriously",
            "Response explains return/refund options clearly within the return policy",
        ])],
    )

    return suite


def main():
    from opik_optimizer_framework import OptimizationContext, run_optimization

    if OPIK_URL:
        os.environ["OPIK_URL_OVERRIDE"] = OPIK_URL

    client = opik.Opik(workspace=OPIK_WORKSPACE, api_key=OPIK_API_KEY)

    logger.info("Creating suite '%s' with 5 hard items", SUITE_NAME)
    suite = _build_suite(client)
    dataset_items = list(suite.dataset.get_items())

    optimizer_type = "GepaV2Optimizer"
    optimization = client.create_optimization(
        dataset_name=SUITE_NAME,
        objective_name=OBJECTIVE_NAME,
        name=OPTIMIZATION_NAME,
        metadata={"optimizer": optimizer_type},
    )
    optimization_id = optimization.id
    logger.info("Optimization: %s", optimization_id)

    context = OptimizationContext(
        optimization_id=optimization_id,
        dataset_name=SUITE_NAME,
        prompt_messages=PROMPT_MESSAGES,
        model=MODEL,
        model_parameters={"temperature": 0.7, "max_tokens": 512},
        metric_type=OBJECTIVE_NAME,
        metric_parameters={},
        optimizer_type=optimizer_type,
        optimizer_parameters={
            "max_candidates": 3,
            "reflection_minibatch_size": 3,
            "candidate_selection_strategy": "pareto",
            "seed": 42,
        },
        baseline_config={
            "prompt_messages": PROMPT_MESSAGES,
            "model": MODEL,
            "model_parameters": {"temperature": 0.7, "max_tokens": 512},
        },
    )

    logger.info("Starting optimization")
    try:
        result = run_optimization(context=context, client=client, dataset_items=dataset_items)
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

    print(f"\n  Trajectory:")
    for trial in result.all_trials:
        parents = trial.parent_candidate_ids or []
        print(
            f"    step={trial.step_index:2d}  score={trial.score:.4f}  "
            f"candidate={trial.candidate_id[:8]}  parents={[p[:8] for p in parents]}"
        )

    print("=" * 60)
    client.end()


if __name__ == "__main__":
    if not os.environ.get("OPENAI_API_KEY") and "gpt" in MODEL.lower():
        print("ERROR: OPENAI_API_KEY not set")
        sys.exit(1)
    main()
