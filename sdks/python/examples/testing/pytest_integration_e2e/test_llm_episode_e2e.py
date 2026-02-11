import pytest

from opik import llm_episode
from opik.simulation import (
    EpisodeAssertion,
    EpisodeBudgetMetric,
    EpisodeBudgets,
    EpisodeResult,
    EpisodeScore,
    SimulatedUser,
    build_trajectory_summary,
    make_max_turns_assertion,
    make_tool_call_budget,
    run_simulation,
)


def lookup_order(order_id: str):
    return {"order_id": order_id, "status": "delivered", "eligible_for_refund": True}


def create_refund(order_id: str):
    return {"order_id": order_id, "refund_id": f"rfnd-{order_id.lower()}", "status": "submitted"}


def get_refund_scenario():
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
    def __init__(self):
        self.trajectory_by_thread = {}
        self.state_by_thread = {}

    def _record(self, thread_id, action, details):
        self.trajectory_by_thread.setdefault(thread_id, []).append(
            {"action": action, "details": details}
        )

    def __call__(self, user_message: str, *, thread_id: str, **kwargs):
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
            self._record(thread_id, "lookup_order", {"order_id": order_id, "status": order["status"]})
            state["order_id"] = order_id
            return {
                "role": "assistant",
                "content": (
                    f"Thanks. I found order {order_id} and it is eligible for refund. "
                    "Please confirm and I will submit it."
                ),
            }

        if state["order_id"] is not None and "yes" in normalized and not state["refund_submitted"]:
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
    scenario_id = scenario["scenario_id"]
    agent = RefundAgent()

    user_simulator = SimulatedUser(
        persona=scenario["user_persona"],
        fixed_responses=scenario["user_messages"],
    )

    simulation = run_simulation(
        app=agent,
        user_simulator=user_simulator,
        max_turns=scenario["max_turns"],
        project_name=scenario["project_name"],
        tags=scenario["tags"],
    )

    conversation_history = simulation["conversation_history"]
    thread_id = simulation["thread_id"]
    trajectory = agent.trajectory_by_thread.get(thread_id, [])

    assistant_messages = [
        message["content"]
        for message in conversation_history
        if message["role"] == "assistant"
    ]
    refund_submitted = any("submitted your refund request" in message for message in assistant_messages)
    asked_for_order_id = any("Please share your order id" in message for message in assistant_messages)

    turn_assertion = make_max_turns_assertion(
        conversation_history=conversation_history,
        max_turns=scenario["max_turns"],
        name="max_turns_guardrail",
    )

    episode = EpisodeResult(
        scenario_id=scenario_id,
        thread_id=thread_id,
        assertions=[
            EpisodeAssertion(
                name="assistant_replied_each_turn",
                passed=len(assistant_messages) == scenario["max_turns"],
                reason=f"assistant_messages={len(assistant_messages)}",
            ),
            EpisodeAssertion(
                name="policy_requires_order_id_before_refund",
                passed=asked_for_order_id == scenario["expect_order_id_prompt"],
                reason=(
                    "assistant order-id prompt behavior matched scenario expectation: "
                    f"expected={scenario['expect_order_id_prompt']}, observed={asked_for_order_id}"
                ),
            ),
            EpisodeAssertion(
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
            EpisodeScore(
                name="goal_completion",
                value=1.0
                if refund_submitted == scenario["expect_refund_submitted"]
                else 0.0,
                reason="scenario goal reached according to expected outcome",
            ),
            EpisodeScore(
                name="instruction_adherence",
                value=1.0
                if asked_for_order_id == scenario["expect_order_id_prompt"]
                else 0.5,
                reason="agent followed scenario-specific policy behavior",
            ),
        ],
        budgets=EpisodeBudgets(
            max_turns=EpisodeBudgetMetric(
                used=float(len(conversation_history) // 2),
                limit=float(scenario["max_turns"]),
                unit="count",
            ),
            tool_calls=make_tool_call_budget(trajectory=trajectory, limit=scenario["tool_call_limit"]),
        ),
        trajectory_summary=build_trajectory_summary(trajectory),
        metadata={
            "project_name": simulation.get("project_name"),
            "tags": simulation.get("tags"),
            "tool_actions": [step["action"] for step in trajectory],
        },
    )

    # Native pytest failure signal, while still returning EpisodeResult for Opik artifacts.
    assert episode.is_passing(), episode.model_dump_json(indent=2)
    return episode
