#!/usr/bin/env python
"""GEPA v2 stress test with 30 customer support items of mixed difficulty."""

import logging
import os
import sys
import time

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("gepa-v2-30items")

OPIK_URL = os.environ.get("OPIK_URL_OVERRIDE")
OPIK_WORKSPACE = os.environ.get("OPIK_WORKSPACE", "default")
OPIK_API_KEY = os.environ.get("OPIK_API_KEY")

SUITE_NAME = f"gepa-v2-30items-{int(time.time())}"
OPTIMIZATION_NAME = "gepa-v2-30items"
OBJECTIVE_NAME = "pass_rate"
MODEL = os.environ.get("OPIK_TEST_MODEL", "gpt-4o-mini")


def _update_optimization_status(client, optimization_id, status):
    client.rest_client.optimizations.update_optimizations_by_id(
        optimization_id, status=status,
    )


def _build_suite(client):
    from opik.evaluation.suite_evaluators import LLMJudge

    suite = client.create_evaluation_suite(
        name=SUITE_NAME,
        description="Customer support stress test (30 items, mixed difficulty)",
        evaluators=[
            LLMJudge(assertions=["Response is relevant to the user question"])
        ],
        execution_policy={"runs_per_item": 1, "pass_threshold": 1},
    )

    # === EASY (10 items) — generic assertions, should pass with baseline ===

    suite.add_item(data={
        "question": "What are your store hours?",
        "context": "General inquiry",
    })

    suite.add_item(data={
        "question": "Do you offer free shipping?",
        "context": "Customer browsing website",
    })

    suite.add_item(data={
        "question": "How do I track my order?",
        "context": "Order #55123, shipped 2 days ago",
    })

    suite.add_item(data={
        "question": "Can I change the delivery address on my order?",
        "context": "Order #55200, not yet shipped",
    })

    suite.add_item(data={
        "question": "What payment methods do you accept?",
        "context": "New customer, first purchase",
    })

    suite.add_item(data={
        "question": "Do you have a loyalty program?",
        "context": "Returning customer, 3 previous orders",
    })

    suite.add_item(data={
        "question": "Where can I find your size chart?",
        "context": "Shopping for clothing",
    })

    suite.add_item(data={
        "question": "Is this item in stock?",
        "context": "Product SKU: SHOE-RUN-42, showing 'low stock' on website",
    })

    suite.add_item(data={
        "question": "How long does standard shipping take?",
        "context": "Customer in California",
    })

    suite.add_item(data={
        "question": "Can I return a sale item?",
        "context": "Item purchased during clearance event, 5 days ago",
    })

    # === MEDIUM (10 items) — specific assertions, may or may not pass ===

    suite.add_item(
        data={
            "question": "I received the wrong color. I ordered blue but got red.",
            "context": "Order #67890, delivered yesterday",
        },
        evaluators=[LLMJudge(assertions=[
            "Response apologizes for the wrong item being sent",
            "Response offers a clear exchange or return process",
        ])],
    )

    suite.add_item(
        data={
            "question": "My discount code SAVE20 isn't working at checkout.",
            "context": "Code is valid, minimum purchase $50, cart total $45",
        },
        evaluators=[LLMJudge(assertions=[
            "Response helps troubleshoot why the code isn't working",
            "Response mentions possible reasons like minimum purchase requirements",
        ])],
    )

    suite.add_item(
        data={
            "question": "I want to cancel my order but the cancel button is greyed out.",
            "context": "Order #78901, placed 2 hours ago, status: processing",
        },
        evaluators=[LLMJudge(assertions=[
            "Response explains why the button may be disabled",
            "Response offers an alternative way to cancel",
        ])],
    )

    suite.add_item(
        data={
            "question": "The product page says waterproof but mine leaked in light rain.",
            "context": "Product: AllWeather Jacket v3, purchased 1 week ago",
        },
        evaluators=[LLMJudge(assertions=[
            "Response takes the product quality concern seriously",
            "Response offers a return, replacement, or warranty claim path",
        ])],
    )

    suite.add_item(
        data={
            "question": "I was charged twice for the same order!",
            "context": "Order #82345, two identical charges of $89.99 on credit card statement",
        },
        evaluators=[LLMJudge(assertions=[
            "Response acknowledges the double charge concern",
            "Response explains how the duplicate charge will be resolved",
        ])],
    )

    suite.add_item(
        data={
            "question": "Can I use my employee discount on top of the holiday sale?",
            "context": "Employee ID verified, 15% employee discount, current 25% holiday sale",
        },
        evaluators=[LLMJudge(assertions=[
            "Response addresses whether discounts can be stacked",
            "Response is honest if the policy is unknown rather than guessing",
        ])],
    )

    suite.add_item(
        data={
            "question": "My package shows delivered but I never got it.",
            "context": "Tracking: USPS delivered to front door 3 days ago",
        },
        evaluators=[LLMJudge(assertions=[
            "Response suggests concrete troubleshooting steps",
            "Response offers to open an investigation or send a replacement",
        ])],
    )

    suite.add_item(
        data={
            "question": "I need to return a gift but I don't have the receipt.",
            "context": "Item is from this store, still has tags, no order number available",
        },
        evaluators=[LLMJudge(assertions=[
            "Response explains the return policy for items without receipts",
            "Response suggests alternative ways to locate the order",
        ])],
    )

    suite.add_item(
        data={
            "question": "The assembly instructions for my bookshelf are missing page 3.",
            "context": "Product: ModernHome Bookshelf XL, purchased online",
        },
        evaluators=[LLMJudge(assertions=[
            "Response offers a practical solution to get the missing instructions",
        ])],
    )

    suite.add_item(
        data={
            "question": "Why was my review removed? I gave honest feedback!",
            "context": "1-star review posted 2 days ago, product: BlendMaster Pro",
        },
        evaluators=[LLMJudge(assertions=[
            "Response treats the concern respectfully without being dismissive",
            "Response explains possible reasons for review removal",
        ])],
    )

    # === HARD (10 items) — multi-assertion, specific behaviors required ===

    suite.add_item(
        data={
            "question": (
                "I think someone hacked my account! I see 3 orders I didn't make "
                "in the last 24 hours!"
            ),
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
                "I ordered a laptop 2 weeks ago, it arrived broken, I called multiple "
                "times and each time was told someone would call back but nobody did. "
                "I am EXTREMELY frustrated and want a FULL refund PLUS compensation!"
            ),
            "context": "Order #98765, multiple previous support tickets closed without resolution",
        },
        evaluators=[LLMJudge(assertions=[
            "Response sincerely apologizes for the repeated failures in service",
            "Response acknowledges the frustration of multiple unreturned callbacks",
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
                "but it barely blocks any noise. This is false advertising!"
            ),
            "context": "Product: WH-2024-BLK, purchased 5 days ago, 30-day return policy",
        },
        evaluators=[LLMJudge(assertions=[
            "Response acknowledges the customer's frustration about product not meeting expectations",
            "Response addresses the false advertising concern seriously",
            "Response explains return or refund options clearly within the return policy",
        ])],
    )

    suite.add_item(
        data={
            "question": (
                "I placed a $2,000 order for my business but the invoice has the wrong "
                "company name and tax ID. I need this corrected ASAP for my accountant!"
            ),
            "context": "Business account, order #B-45678, invoice already generated",
        },
        evaluators=[LLMJudge(assertions=[
            "Response understands the urgency of the business invoice correction",
            "Response provides a clear path to get the invoice corrected",
            "Response does not suggest the customer just edit the PDF themselves",
        ])],
    )

    suite.add_item(
        data={
            "question": (
                "My elderly mother accidentally subscribed to 4 premium add-ons she doesn't "
                "understand. She's been charged $120/month for 3 months. I want all charges "
                "reversed!"
            ),
            "context": "Account holder is 78, tech-illiterate, subscriptions started via pop-ups",
        },
        evaluators=[LLMJudge(assertions=[
            "Response shows empathy for the vulnerable customer situation",
            "Response addresses the request for charge reversal directly",
            "Response offers to cancel the unwanted subscriptions",
            "Response does not blame the customer for subscribing",
        ])],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )

    suite.add_item(
        data={
            "question": (
                "I bought a baby car seat and just saw a recall notice for this exact model! "
                "My child has been using it for 2 weeks. What do I do?!"
            ),
            "context": "Product: SafeRide Infant Seat Model SR-100, purchased 2 weeks ago",
        },
        evaluators=[LLMJudge(assertions=[
            "Response treats the child safety concern with maximum urgency",
            "Response advises to stop using the product immediately",
            "Response provides clear next steps for the recall process",
        ])],
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

    suite.add_item(
        data={
            "question": (
                "I'm a content creator with 500K followers. I received a defective product "
                "and I'm about to post a negative review unless this gets resolved NOW."
            ),
            "context": "Verified influencer account, product: SmartWatch Ultra, defective screen",
        },
        evaluators=[LLMJudge(assertions=[
            "Response addresses the product defect concern professionally",
            "Response does not treat the influencer threat as coercion",
            "Response offers the same resolution any customer would receive",
        ])],
    )

    return suite


def main():
    import opik
    from opik_optimizer_framework import OptimizationContext, run_optimization

    if OPIK_URL:
        os.environ["OPIK_URL_OVERRIDE"] = OPIK_URL

    client = opik.Opik(workspace=OPIK_WORKSPACE, api_key=OPIK_API_KEY)

    logger.info("Creating suite '%s' with 30 items", SUITE_NAME)
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
    logger.info("Optimization: %s", optimization_id)

    context = OptimizationContext(
        optimization_id=optimization_id,
        dataset_name=SUITE_NAME,
        model=MODEL,
        metric_type=OBJECTIVE_NAME,
        optimizer_type=optimizer_type,
        optimizer_parameters={
            "max_candidates": 10,
            "reflection_minibatch_size": 7,
            "seed": 42,
        },
        optimizable_keys=["system_prompt", "user_message"],
        baseline_config={
            "system_prompt": (
                "You are a helpful customer support agent for an e-commerce company. "
                "Be professional, empathetic, and provide clear, actionable responses. "
                "If you don't know something, be honest about it."
            ),
            "user_message": "Customer question: {question}\nAdditional context: {context}",
            "model": MODEL,
            "model_parameters": {"temperature": 0.7, "max_tokens": 512},
        },
    )

    logger.info("Starting optimization (model=%s)", MODEL)
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
