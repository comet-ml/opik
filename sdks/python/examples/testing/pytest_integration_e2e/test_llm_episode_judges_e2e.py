"""End-to-end llm_episode demo with built-in and custom LLM judges.

This example shows how episode tests can incorporate judge-based quality checks:
- Built-in judge (`AnswerRelevance`) for a standard signal.
- Custom judge (`GEval`) for domain-specific support quality criteria.
"""

import json
import os

import pytest

from opik import llm_episode
from opik.evaluation import metrics
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
from demo_helpers import get_demo_logger, log_episode_panel, log_json_debug

LOGGER = get_demo_logger("pytest_integration_e2e.judges")
BUILT_IN_JUDGE_THRESHOLD = 0.6
CUSTOM_JUDGE_THRESHOLD = 0.4
JUDGE_TEMPERATURE = 1.0


class SupportResolutionAgent:
    """Deterministic support agent used to keep judge demos reproducible."""

    def __init__(self):
        self.trajectory_by_thread = {}
        self.state_by_thread = {}

    def _record(self, thread_id: str, action: str, details: dict) -> None:
        self.trajectory_by_thread.setdefault(thread_id, []).append(
            {"action": action, "details": details}
        )

    def __call__(self, user_message: str, *, thread_id: str, **kwargs):
        """Return branch-specific support responses and capture mock tool calls."""
        normalized = user_message.lower()
        state = self.state_by_thread.setdefault(thread_id, {"workflow": None})

        if "reset" in normalized and "password" in normalized:
            state["workflow"] = "password_reset"
            self._record(
                thread_id, "lookup_help_article", {"article_id": "KB-RESET-02"}
            )
            return {
                "role": "assistant",
                "content": (
                    "Use the password reset link on the sign-in page, then check your inbox "
                    "for a code. If the email does not arrive in 5 minutes, I can open a "
                    "support ticket for you."
                ),
            }

        if "billing" in normalized or "charged" in normalized:
            state["workflow"] = "billing_dispute"
            self._record(thread_id, "open_billing_ticket", {"priority": "normal"})
            return {
                "role": "assistant",
                "content": (
                    "I can help with that billing issue. Please confirm the last 4 digits of "
                    "the payment method and I will submit a dispute ticket for review within "
                    "24 hours."
                ),
            }

        if state["workflow"] == "password_reset":
            return {
                "role": "assistant",
                "content": (
                    "Next step: click 'Forgot password', enter your account email, then use "
                    "the reset code from email to set a new password. If no code arrives in "
                    "5 minutes, I can escalate to support and open a recovery ticket."
                ),
            }

        if state["workflow"] == "billing_dispute":
            return {
                "role": "assistant",
                "content": (
                    "To dispute the duplicate charge: confirm the last 4 digits and charge "
                    "date, then I will submit a billing dispute ticket. Billing Support reviews "
                    "it within 24 hours and emails next steps."
                ),
            }

        return {
            "role": "assistant",
            "content": "I can route this request to the right support workflow.",
        }


def judge_scenarios():
    """Scenario fixtures used to evaluate judge behavior across support intents."""
    return [
        {
            "scenario_id": "judge_password_reset_v1",
            "project_name": "pytest-episode-e2e",
            "tags": ["simulation", "judges", "support"],
            "max_turns": 2,
            "tool_call_limit": 2,
            "user_persona": "You are locked out and need a practical password reset flow.",
            "user_messages": [
                "I cannot log in and need to reset my password.",
                "What should I do next?",
            ],
            "eval_input": "What is the concrete next step to reset my password?",
            "goal": "Provide actionable password reset guidance.",
        },
        {
            "scenario_id": "judge_billing_dispute_v1",
            "project_name": "pytest-episode-e2e",
            "tags": ["simulation", "judges", "billing"],
            "max_turns": 2,
            "tool_call_limit": 2,
            "user_persona": "You were charged incorrectly and need a clear dispute path.",
            "user_messages": [
                "I was charged twice for my subscription.",
                "How do I dispute this quickly?",
            ],
            "eval_input": "How do I dispute a duplicate subscription charge?",
            "goal": "Explain a clear billing dispute path.",
        },
    ]


def _require_openai_api_key() -> None:
    """Skip judge demos when no model key is configured locally."""
    if not os.getenv("OPENAI_API_KEY"):
        pytest.skip("Set OPENAI_API_KEY to run gpt-5-nano judge examples")


@pytest.mark.filterwarnings(
    "ignore:Test functions should return None:pytest.PytestReturnNotNoneWarning"
)
@pytest.mark.filterwarnings("ignore:There is no current event loop:DeprecationWarning")
@pytest.mark.parametrize("scenario", judge_scenarios())
@llm_episode(scenario_id_key="scenario_id")
def test_llm_episode_with_builtin_and_custom_judges(scenario):
    """Run simulation, score with two judges, then gate on fixed demo thresholds."""
    _require_openai_api_key()

    agent = SupportResolutionAgent()
    simulation = run_simulation(
        app=agent,
        user_simulator=SimulatedUser(
            persona=scenario["user_persona"], fixed_responses=scenario["user_messages"]
        ),
        max_turns=scenario["max_turns"],
        project_name=scenario["project_name"],
        tags=scenario["tags"],
        track_app_calls=False,
    )

    thread_id = simulation["thread_id"]
    trajectory = agent.trajectory_by_thread.get(thread_id, [])
    trajectory_summary = build_trajectory_summary(trajectory)
    assistant_messages = [
        message["content"]
        for message in simulation["conversation_history"]
        if message["role"] == "assistant"
    ]
    final_assistant_response = assistant_messages[-1] if assistant_messages else ""

    built_in_judge = metrics.AnswerRelevance(
        name="answer_relevance_builtin_judge",
        model="gpt-5-nano",
        temperature=JUDGE_TEMPERATURE,
        require_context=False,
        track=False,
    )
    built_in_result = built_in_judge.score(
        input=scenario["eval_input"],
        output=final_assistant_response,
    )

    custom_judge = metrics.GEval(
        name="custom_support_resolution_judge",
        model="gpt-5-nano",
        temperature=JUDGE_TEMPERATURE,
        track=False,
        task_introduction=(
            "You are a support QA judge evaluating whether an assistant response is "
            "actionable, safe, and aligned with the stated user goal."
        ),
        evaluation_criteria=(
            "Score from 1 to 5. High scores require specific next steps, accurate "
            "routing language, and no fabricated claims. Low scores are vague, "
            "off-topic, or unsafe."
        ),
    )
    custom_judge_input = json.dumps(
        {
            "scenario_id": scenario["scenario_id"],
            "user_goal": scenario["goal"],
            "assistant_response": final_assistant_response,
            "trajectory": trajectory,
        },
        sort_keys=True,
    )
    custom_result = custom_judge.score(output=custom_judge_input)

    turn_assertion = make_max_turns_assertion(
        conversation_history=simulation["conversation_history"],
        max_turns=scenario["max_turns"],
        name="max_turns_guardrail",
    )

    episode = EpisodeResult(
        scenario_id=scenario["scenario_id"],
        thread_id=thread_id,
        assertions=[
            # Distinguish "judge executed" from "judge score met threshold".
            EpisodeAssertion(
                name="builtin_judge_scored",
                passed=not built_in_result.scoring_failed,
                reason=built_in_result.reason,
            ),
            EpisodeAssertion(
                name="custom_judge_scored",
                passed=not custom_result.scoring_failed,
                reason=custom_result.reason,
            ),
            EpisodeAssertion(
                name="builtin_judge_meets_threshold",
                passed=built_in_result.value >= BUILT_IN_JUDGE_THRESHOLD,
                reason=(
                    f"answer_relevance={built_in_result.value:.3f}, "
                    f"threshold={BUILT_IN_JUDGE_THRESHOLD:.3f}"
                ),
            ),
            EpisodeAssertion(
                name="custom_judge_meets_threshold",
                passed=custom_result.value >= CUSTOM_JUDGE_THRESHOLD,
                reason=(
                    f"custom_support_resolution={custom_result.value:.3f}, "
                    f"threshold={CUSTOM_JUDGE_THRESHOLD:.3f}"
                ),
            ),
            turn_assertion,
        ],
        scores=[
            EpisodeScore(
                name=built_in_result.name,
                value=float(built_in_result.value),
                reason=built_in_result.reason,
            ),
            EpisodeScore(
                name=custom_result.name,
                value=float(custom_result.value),
                reason=custom_result.reason,
            ),
        ],
        budgets=EpisodeBudgets(
            max_turns=EpisodeBudgetMetric(
                used=float(len(simulation["conversation_history"]) // 2),
                limit=float(scenario["max_turns"]),
                unit="count",
            ),
            tool_calls=make_tool_call_budget(
                trajectory=trajectory, limit=scenario["tool_call_limit"]
            ),
        ),
        trajectory_summary=trajectory_summary,
        metadata={
            "tags": simulation.get("tags"),
            "judge_model": "gpt-5-nano",
            "judge_temperature": JUDGE_TEMPERATURE,
            "built_in_judge": built_in_result.name,
            "custom_judge": custom_result.name,
            "built_in_judge_threshold": BUILT_IN_JUDGE_THRESHOLD,
            "custom_judge_threshold": CUSTOM_JUDGE_THRESHOLD,
        },
    )

    log_episode_panel(
        LOGGER,
        scenario_id=scenario["scenario_id"],
        thread_id=thread_id,
        episode=episode,
        turns=len(simulation["conversation_history"]) // 2,
        tool_calls=trajectory_summary["tool_calls_count"],
        extras={
            "Built-in judge": (
                f"{built_in_result.name}={built_in_result.value:.3f}"
                f" (threshold={BUILT_IN_JUDGE_THRESHOLD:.3f})"
            ),
            "Custom judge": (
                f"{custom_result.name}={custom_result.value:.3f}"
                f" (threshold={CUSTOM_JUDGE_THRESHOLD:.3f})"
            ),
        },
    )
    log_json_debug(
        LOGGER,
        "judge_results",
        {
            "built_in": {
                "name": built_in_result.name,
                "value": built_in_result.value,
                "reason": built_in_result.reason,
                "scoring_failed": built_in_result.scoring_failed,
            },
            "custom": {
                "name": custom_result.name,
                "value": custom_result.value,
                "reason": custom_result.reason,
                "scoring_failed": custom_result.scoring_failed,
            },
            "episode": episode.model_dump(),
        },
    )

    assert episode.is_passing(), episode.model_dump_json(indent=2)
    return episode
