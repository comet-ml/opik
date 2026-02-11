import pytest

from opik import llm_episode
from opik.simulation import (
    EpisodeAssertion,
    EpisodeBudgetMetric,
    EpisodeBudgets,
    EpisodeResult,
    SimulatedUser,
    run_simulation,
)


def simple_customer_support_agent(user_message: str, *, thread_id: str, **kwargs):
    return {"role": "assistant", "content": f"Ack: {user_message}"}


@pytest.mark.filterwarnings(
    "ignore:Test functions should return None:pytest.PytestReturnNotNoneWarning"
)
@llm_episode(scenario_id_key="scenario_id")
def test_refund_episode_ci_gate(scenario_id: str = "refund_flow_v1"):
    user_simulator = SimulatedUser(
        persona="You are a customer requesting a refund",
        fixed_responses=["I need a refund", "My order arrived damaged"],
    )

    simulation = run_simulation(
        app=simple_customer_support_agent,
        user_simulator=user_simulator,
        max_turns=2,
        project_name="pytest-episode-e2e",
        tags=["simulation", "refund"],
    )

    assistant_messages = [
        message["content"]
        for message in simulation["conversation_history"]
        if message["role"] == "assistant"
    ]

    return EpisodeResult(
        scenario_id=scenario_id,
        thread_id=simulation["thread_id"],
        assertions=[
            EpisodeAssertion(
                name="assistant_replied_each_turn",
                passed=len(assistant_messages) == 2,
                reason=f"assistant_messages={len(assistant_messages)}",
            ),
            EpisodeAssertion(
                name="ack_prefix_present",
                passed=all(message.startswith("Ack:") for message in assistant_messages),
                reason="all assistant replies start with Ack:",
            ),
        ],
        budgets=EpisodeBudgets(
            max_turns=EpisodeBudgetMetric(used=2, limit=2, unit="count")
        ),
        metadata={"project_name": simulation.get("project_name")},
    )
