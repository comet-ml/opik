"""End-to-end llm_episode demo for a deterministic refund workflow.

This example shows a realistic pattern for CI-oriented episode tests:
1) Define scenario fixtures (persona, turns, expected behaviors, budgets).
2) Run a multi-turn simulation with a deterministic agent + user simulator.
3) Convert the run into an EpisodeResult with explicit assertions/scores/budgets.
4) Use native pytest assertions while still returning EpisodeResult for Opik artifacts.
"""

import os

import pytest

from opik import llm_episode, simulation
from demo_helpers import (
    get_demo_logger,
    log_conversation_turns_debug,
    log_episode_panel,
    log_json_debug,
    log_trajectory_steps_debug,
)

LOGGER = get_demo_logger("pytest_integration_e2e.episode")


def print_episode_debug(
    scenario_id: str,
    simulation_run: dict,
    trajectory: list[dict],
    episode: simulation.EpisodeResult,
) -> None:
    """Log episode output in two tiers: concise info and full debug payloads."""
    trajectory_summary = simulation.build_trajectory_summary(trajectory)

    log_episode_panel(
        LOGGER,
        scenario_id=scenario_id,
        thread_id=simulation_run.get("thread_id"),
        episode=episode,
        turns=len(simulation_run["conversation_history"]) // 2,
        tool_calls=trajectory_summary["tool_calls_count"],
        extras={"Project": simulation_run.get("project_name")},
    )
    if os.getenv("OPIK_EXAMPLE_LOG_LEVEL", "DEBUG").upper() != "DEBUG":
        LOGGER.info(
            "set OPIK_EXAMPLE_LOG_LEVEL=DEBUG for full simulation/trajectory/episode dumps"
        )

    log_conversation_turns_debug(LOGGER, simulation_run["conversation_history"])
    log_trajectory_steps_debug(LOGGER, trajectory)
    log_json_debug(LOGGER, "episode", episode.model_dump())


def lookup_order(order_id: str):
    """Fake lookup tool used by the deterministic demo agent."""
    return {"order_id": order_id, "status": "delivered", "eligible_for_refund": True}


def create_refund(order_id: str):
    """Fake refund tool used by the deterministic demo agent."""
    return {
        "order_id": order_id,
        "refund_id": f"rfnd-{order_id.lower()}",
        "status": "submitted",
    }


def get_refund_scenario():
    """Scenario fixtures used to parameterize episode coverage for this demo."""
    return [
        {
            "scenario_id": "refund_flow_v1",
            "max_turns": 3,
            "tool_call_limit": 3,
            "project_name": "pytest-episode-e2e",
            "tags": ["simulation", "refund", "policy-check"],
            "user_persona": (
                "You are a frustrated customer requesting a refund for a damaged package. "
                "Provide concise answers and give your order id when asked."
            ),
            "user_messages": [
                "Hi, I want a refund because the package arrived damaged.",
                "My order id is ORD-1234.",
                "Yes, please process the refund now.",
            ],
            "expect_refund_submitted": True,
            "expect_order_id_prompt": True,
        },
        {
            "scenario_id": "shipping_only_v1",
            "max_turns": 3,
            "tool_call_limit": 1,
            "project_name": "pytest-episode-e2e",
            "tags": ["simulation", "shipping", "policy-check"],
            "user_persona": (
                "You are a customer asking for shipping updates only. "
                "Do not ask for a refund."
            ),
            "user_messages": [
                "Where is my package?",
                "I only need tracking details.",
                "Thanks, no refund needed.",
            ],
            "expect_refund_submitted": False,
            "expect_order_id_prompt": True,
        },
    ]


class RefundAgent:
    """Stateful deterministic agent that simulates a small refund flow."""

    def __init__(self):
        self.trajectory_by_thread = {}
        self.state_by_thread = {}

    def _record(self, thread_id, action, details):
        self.trajectory_by_thread.setdefault(thread_id, []).append(
            {"action": action, "details": details}
        )

    def __call__(self, user_message: str, *, thread_id: str, **kwargs):
        """Route each user turn through a simple policy + tool path."""
        state = self.state_by_thread.setdefault(
            thread_id,
            {
                "asked_for_order_id": False,
                "order_id": None,
                "refund_submitted": False,
            },
        )

        normalized = user_message.lower()

        if state["order_id"] is None and "ord-" not in normalized:
            state["asked_for_order_id"] = True
            return {
                "role": "assistant",
                "content": (
                    "I can help with that. Please share your order id (for example ORD-1234) "
                    "so I can verify refund eligibility."
                ),
            }

        if state["order_id"] is None and "ord-" in normalized:
            order_id = user_message.upper().split("ORD-")[1].split()[0].strip(".,!?")
            order_id = f"ORD-{order_id}"
            order = lookup_order(order_id)
            self._record(
                thread_id,
                "lookup_order",
                {"order_id": order_id, "status": order["status"]},
            )
            state["order_id"] = order_id
            return {
                "role": "assistant",
                "content": (
                    f"Thanks. I found order {order_id} and it is eligible for refund. "
                    "Please confirm and I will submit it."
                ),
            }

        if (
            state["order_id"] is not None
            and "yes" in normalized
            and not state["refund_submitted"]
        ):
            refund = create_refund(state["order_id"])
            self._record(
                thread_id,
                "create_refund",
                {"order_id": state["order_id"], "refund_id": refund["refund_id"]},
            )
            state["refund_submitted"] = True
            return {
                "role": "assistant",
                "content": (
                    f"Done. I submitted your refund request {refund['refund_id']}. "
                    "You will receive a confirmation email shortly."
                ),
            }

        return {
            "role": "assistant",
            "content": "I can continue helping with this refund request.",
        }


@pytest.mark.filterwarnings(
    "ignore:Test functions should return None:pytest.PytestReturnNotNoneWarning"
)
@pytest.mark.parametrize("scenario", get_refund_scenario())
@llm_episode(scenario_id_key="scenario_id")
def test_refund_episode_ci_gate(scenario):
    """Validate refund episode behavior with CI-friendly deterministic checks."""
    scenario_id = scenario["scenario_id"]
    agent = RefundAgent()

    # Fixed responses keep the simulation deterministic across local and CI runs.
    user_simulator = simulation.SimulatedUser(
        persona=scenario["user_persona"],
        fixed_responses=scenario["user_messages"],
    )

    # run_simulation drives the full multi-turn episode and returns traceable artifacts.
    simulation_run = simulation.run_simulation(
        app=agent,
        user_simulator=user_simulator,
        max_turns=scenario["max_turns"],
        project_name=scenario["project_name"],
        tags=scenario["tags"],
        track_app_calls=False,
    )

    conversation_history = simulation_run["conversation_history"]
    thread_id = simulation_run["thread_id"]
    trajectory = agent.trajectory_by_thread.get(thread_id, [])

    assistant_messages = [
        message["content"]
        for message in conversation_history
        if message["role"] == "assistant"
    ]
    refund_submitted = any(
        "submitted your refund request" in message for message in assistant_messages
    )
    asked_for_order_id = any(
        "Please share your order id" in message for message in assistant_messages
    )

    turn_assertion = simulation.make_max_turns_assertion(
        conversation_history=conversation_history,
        max_turns=scenario["max_turns"],
        name="max_turns_guardrail",
    )

    episode = simulation.EpisodeResult(
        scenario_id=scenario_id,
        thread_id=thread_id,
        assertions=[
            # Assertions: hard constraints that should gate PRs.
            simulation.EpisodeAssertion(
                name="assistant_replied_each_turn",
                passed=len(assistant_messages) == scenario["max_turns"],
                reason=f"assistant_messages={len(assistant_messages)}",
            ),
            simulation.EpisodeAssertion(
                name="policy_requires_order_id_before_refund",
                passed=asked_for_order_id == scenario["expect_order_id_prompt"],
                reason=(
                    "assistant order-id prompt behavior matched scenario expectation: "
                    f"expected={scenario['expect_order_id_prompt']}, observed={asked_for_order_id}"
                ),
            ),
            simulation.EpisodeAssertion(
                name="refund_submission_completed",
                passed=refund_submitted == scenario["expect_refund_submitted"],
                reason=(
                    "refund completion matched scenario expectation: "
                    f"expected={scenario['expect_refund_submitted']}, observed={refund_submitted}"
                ),
            ),
            turn_assertion,
        ],
        scores=[
            # Scores: softer quality signals for trend tracking.
            simulation.EpisodeScore(
                name="goal_completion",
                value=1.0
                if refund_submitted == scenario["expect_refund_submitted"]
                else 0.0,
                reason="scenario goal reached according to expected outcome",
            ),
            simulation.EpisodeScore(
                name="instruction_adherence",
                value=1.0
                if asked_for_order_id == scenario["expect_order_id_prompt"]
                else 0.5,
                reason="agent followed scenario-specific policy behavior",
            ),
        ],
        budgets=simulation.EpisodeBudgets(
            # Budgets capture resource and control-plane constraints.
            max_turns=simulation.EpisodeBudgetMetric(
                used=float(len(conversation_history) // 2),
                limit=float(scenario["max_turns"]),
                unit="count",
            ),
            tool_calls=simulation.make_tool_call_budget(
                trajectory=trajectory, limit=scenario["tool_call_limit"]
            ),
        ),
        trajectory_summary=simulation.build_trajectory_summary(trajectory),
        metadata={
            "project_name": simulation_run.get("project_name"),
            "tags": simulation_run.get("tags"),
            "tool_actions": [step["action"] for step in trajectory],
        },
    )
    print_episode_debug(
        scenario_id=scenario_id,
        simulation_run=simulation_run,
        trajectory=trajectory,
        episode=episode,
    )

    # Native pytest failure signal, while still returning EpisodeResult for Opik artifacts.
    assert episode.is_passing(), episode.model_dump_json(indent=2)
    return episode
