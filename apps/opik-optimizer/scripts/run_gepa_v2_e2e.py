#!/usr/bin/env python
"""
End-to-end test for GepaOptimizer with 20 evaluation suite items.

20 items across 3 difficulty tiers (5 easy, 7 medium, 8 hard) with
assertions designed so that:
  - The baseline prompt scores ~0.40-0.50 pass rate
  - No assertion contradicts another on the same item
  - The optimizer has clear room to improve through prompt evolution

Prerequisites:
  - Local Opik backend running (http://localhost:5173)
  - OPENAI_API_KEY + OPENAI_ORG_ID set in environment
  - pip install -e apps/opik-optimizer
  - pip install -e sdks/python

Usage:
  export OPENAI_API_KEY=sk-...
  export OPENAI_ORG_ID=org-...
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

OPIK_URL = os.environ.get("OPIK_URL_OVERRIDE")
OPIK_WORKSPACE = os.environ.get("OPIK_WORKSPACE", "default")
OPIK_API_KEY = os.environ.get("OPIK_API_KEY")

SUITE_NAME = f"gepa-v2-e2e-{int(time.time())}"
OPTIMIZATION_NAME = "gepa-v2-e2e"
OBJECTIVE_NAME = "pass_rate"

MODEL = os.environ.get("OPIK_TEST_MODEL", "gpt-4o-mini")

PROMPT_MESSAGES = [
    {
        "role": "system",
        "content": (
            "You are a customer support agent for an online store. "
            "Help customers with their questions and issues."
        ),
    },
    {
        "role": "user",
        "content": "Customer question: {question}\nContext: {context}",
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
        description="Customer support e2e test (20 items, 3 difficulty tiers)",
        execution_policy={"runs_per_item": 1, "pass_threshold": 1},
    )

    # =====================================================================
    # EASY (5 items) — should pass with baseline prompt, 1 assertion each
    # =====================================================================

    suite.add_item(
        data={
            "question": "What are your store hours?",
            "context": "General inquiry, weekday afternoon",
        },
        evaluators=[LLMJudge(assertions=[
            "Response attempts to address the store hours question",
        ])],
    )

    suite.add_item(
        data={
            "question": "I received a damaged product. How can I get a refund?",
            "context": "Order #12345, placed 3 days ago",
        },
        evaluators=[LLMJudge(assertions=[
            "Response addresses the refund request",
        ])],
    )

    suite.add_item(
        data={
            "question": "Do you ship internationally? I'm in Germany.",
            "context": "Customer located in Germany, browsing website",
        },
        evaluators=[LLMJudge(assertions=[
            "Response addresses the international shipping question",
        ])],
    )

    suite.add_item(
        data={
            "question": "How do I track my order?",
            "context": "Order #55123, shipped 2 days ago",
        },
        evaluators=[LLMJudge(assertions=[
            "Response explains how to track the order",
        ])],
    )

    suite.add_item(
        data={
            "question": "What payment methods do you accept?",
            "context": "New customer, first purchase",
        },
        evaluators=[LLMJudge(assertions=[
            "Response lists or describes available payment methods",
        ])],
    )

    # =====================================================================
    # MEDIUM (7 items) — require behavioral patterns, 2 assertions each
    # Baseline prompt lacks specific behavioral guidance for these.
    # =====================================================================

    suite.add_item(
        data={
            "question": "Is the XYZ Wireless Headphones compatible with iPhone 15?",
            "context": "Product SKU: WH-2024-BLK",
        },
        evaluators=[LLMJudge(assertions=[
            "Response does not confidently claim compatibility or incompatibility without evidence",
            "Response suggests how the customer can verify compatibility",
        ])],
    )

    suite.add_item(
        data={
            "question": "I want to cancel my premium subscription. This is too expensive.",
            "context": "Customer has been subscribed for 6 months, pays $29.99/month",
        },
        evaluators=[LLMJudge(assertions=[
            "Response provides clear cancellation guidance rather than only trying to retain the customer",
            "Response acknowledges the cost concern without being dismissive",
        ])],
    )

    suite.add_item(
        data={
            "question": "My package shows delivered but I never received it.",
            "context": "Tracking shows USPS delivered to front door 2 days ago",
        },
        evaluators=[LLMJudge(assertions=[
            "Response suggests at least two concrete troubleshooting steps",
            "Response offers to open an investigation or send a replacement if the issue is not resolved",
        ])],
    )

    suite.add_item(
        data={
            "question": "I received the wrong color. I ordered blue but got red.",
            "context": "Order #67890, delivered yesterday",
        },
        evaluators=[LLMJudge(assertions=[
            "Response apologizes for the error",
            "Response explains the exchange or return process step by step",
        ])],
    )

    suite.add_item(
        data={
            "question": "My discount code SAVE20 isn't working at checkout.",
            "context": "Code is valid, minimum purchase $50, cart total $45",
        },
        evaluators=[LLMJudge(assertions=[
            "Response helps troubleshoot why the code isn't working",
            "Response mentions the minimum purchase requirement as a possible reason",
        ])],
    )

    suite.add_item(
        data={
            "question": "Can I return a sale item?",
            "context": "Item purchased during clearance event, 5 days ago, 30-day return policy",
        },
        evaluators=[LLMJudge(assertions=[
            "Response explains the return policy as it applies to sale items",
            "Response does not make up a policy that contradicts the provided context",
        ])],
    )

    suite.add_item(
        data={
            "question": (
                "The product page says waterproof but mine leaked in light rain."
            ),
            "context": "Product: AllWeather Jacket v3, purchased 1 week ago, 30-day return policy",
        },
        evaluators=[LLMJudge(assertions=[
            "Response takes the product quality concern seriously rather than dismissing it",
            "Response offers a return, replacement, or warranty path",
        ])],
    )

    # =====================================================================
    # HARD (8 items) — multi-assertion, require learned behaviors
    # Use runs_per_item=3, pass_threshold=2 for stochastic assertions.
    # =====================================================================

    suite.add_item(
        data={
            "question": "I think someone hacked my account. I see orders I didn't make!",
            "context": "Customer reports unauthorized activity, 3 unknown orders in last 24h",
        },
        evaluators=[LLMJudge(assertions=[
            "Response treats the security concern with urgency rather than a routine tone",
            "Response advises the customer to change their password or secure their account",
            "Response commits to investigating the unauthorized orders",
        ])],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )

    suite.add_item(
        data={
            "question": (
                "I ordered a laptop 2 weeks ago, it arrived broken, I called multiple "
                "times and each time was told someone would call back but nobody did. "
                "I am extremely frustrated and want a full refund PLUS compensation."
            ),
            "context": "Order #98765, multiple previous support tickets closed without resolution",
        },
        evaluators=[LLMJudge(assertions=[
            "Response sincerely apologizes for the repeated service failures",
            "Response explicitly acknowledges that the customer was not called back as promised",
            "Response describes next steps the agent will take to resolve the issue",
            "Response uses hedging language for outcomes rather than absolute guarantees",
        ])],
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
        evaluators=[LLMJudge(assertions=[
            "Response acknowledges the urgency of the birthday deadline",
            "Response offers a specific action to fix the gift card issue rather than generic advice",
            "Response does not tell the customer to simply wait or check back later",
        ])],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )

    suite.add_item(
        data={
            "question": (
                "I'm a loyal customer for 5 years. You just raised prices 20% with no "
                "warning. I feel betrayed. Why should I stay?"
            ),
            "context": "Gold tier loyalty member, $15K lifetime spend, 5-year history",
        },
        evaluators=[LLMJudge(assertions=[
            "Response acknowledges the customer's loyalty and long history",
            "Response addresses the pricing concern directly without deflecting",
            "Response does not deny or lie about the price increase",
        ])],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )

    suite.add_item(
        data={
            "question": "I was charged twice for the same order!",
            "context": "Order #82345, two identical charges of $89.99 on credit card statement",
        },
        evaluators=[LLMJudge(assertions=[
            "Response acknowledges the double charge as a serious billing concern",
            "Response explains the steps that will be taken to resolve the duplicate charge",
            "Response does not blame the customer or suggest they are mistaken without investigating",
        ])],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )

    suite.add_item(
        data={
            "question": (
                "My elderly mother accidentally subscribed to 4 premium add-ons she "
                "doesn't understand. She's been charged $120/month for 3 months. "
                "I want all charges reversed!"
            ),
            "context": "Account holder is 78, tech-illiterate, subscriptions started via pop-ups",
        },
        evaluators=[LLMJudge(assertions=[
            "Response shows empathy for the vulnerable customer situation",
            "Response addresses the request for charge reversal directly",
            "Response offers to cancel the unwanted subscriptions",
            "Response does not blame the account holder for subscribing",
        ])],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )

    suite.add_item(
        data={
            "question": (
                "I bought a baby car seat and just saw a recall notice for this exact "
                "model! My child has been using it for 2 weeks. What do I do?!"
            ),
            "context": "Product: SafeRide Infant Seat Model SR-100, purchased 2 weeks ago",
        },
        evaluators=[LLMJudge(assertions=[
            "Response treats the child safety concern with maximum urgency",
            "Response advises to stop using the product immediately",
            "Response provides clear next steps for the recall process",
        ])],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )

    suite.add_item(
        data={
            "question": (
                "Your website leaked my credit card info! I got 3 fraudulent charges "
                "right after buying from you. I'm reporting you to the authorities!"
            ),
            "context": "Customer placed order yesterday, reports 3 fraudulent charges today",
        },
        evaluators=[LLMJudge(assertions=[
            "Response takes the data security allegation seriously",
            "Response advises the customer to contact their bank immediately",
            "Response does not admit fault or liability for the alleged breach",
            "Response offers to investigate the customer's account security",
        ])],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )

    return suite


def main():
    import opik
    from opik_optimizer_framework import OptimizationContext, run_optimization

    if OPIK_URL:
        os.environ["OPIK_URL_OVERRIDE"] = OPIK_URL
    logger.info(
        "Connecting to Opik (workspace: %s, url: %s)",
        OPIK_WORKSPACE, OPIK_URL or "cloud default",
    )

    client = opik.Opik(workspace=OPIK_WORKSPACE, api_key=OPIK_API_KEY)

    logger.info("Creating evaluation suite '%s' with 20 items", SUITE_NAME)
    suite = _build_suite(client)

    dataset_items = list(suite.dataset.get_items())
    logger.info("Suite has %d items", len(dataset_items))

    optimizer_type = "GepaOptimizer"
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
        "reflection_minibatch_size": 7,
        "candidate_selection_strategy": "pareto",
        "seed": 42,
    }

    context = OptimizationContext(
        optimization_id=optimization_id,
        dataset_name=SUITE_NAME,
        model=MODEL,
        metric_type=OBJECTIVE_NAME,
        optimizer_type=optimizer_type,
        optimizer_parameters=optimizer_parameters,
        optimizable_keys=["system_prompt", "user_message"],
        config_descriptions={
            "system_prompt": "Main customer-facing support agent system prompt",
            "user_message": "User message template with question and context placeholders",
        },
        baseline_config={
            "system_prompt": PROMPT_MESSAGES[0]["content"],
            "user_message": PROMPT_MESSAGES[1]["content"],
            "model": MODEL,
            "model_parameters": {"temperature": 0.7, "max_tokens": 512},
        },
        split_strategy="no_split",
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
        config = result.best_trial.config
        for key in ("system_prompt", "user_message"):
            if key in config:
                print(f"    {key}: {str(config[key])[:100]}...")

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
