from typing import Any, Dict, List

from opik.evaluation.models import base_model


_SYSTEM_PROMPT = """You are an expert evaluator of ReAct-style agent trajectories. Assess how effectively the agent reasoned through the problem and selected appropriate actions.

Evaluation Criteria:
1. Reasoning Quality: Logical, relevant thoughts that guide action selection
2. Action Appropriateness: Actions align with thoughts and progress toward the goal
3. Observation Integration: Effective use of feedback to inform next steps
4. Goal Achievement: Successfully accomplishes the stated objective
5. Efficiency: Reasonable path without unnecessary detours

Scoring Guidelines:
- 0.9-1.0: Excellent reasoning, appropriate actions, achieves goal efficiently
- 0.7-0.8: Good performance with minor issues or inefficiencies
- 0.5-0.6: Adequate but with notable problems in reasoning or actions
- 0.3-0.4: Poor performance, significant flaws but some progress
- 0.0-0.2: Fundamentally flawed, fails to achieve goal

Respond in JSON format:
{
    "score": <float between 0.0 and 1.0>,
    "explanation": "<specific evaluation referencing trajectory steps>"
}"""


def build_messages(
    goal: str,
    trajectory: List[Dict[str, Any]],
    final_result: str,
) -> List[base_model.ConversationDict]:
    """Build the [system, user] message pair for trajectory accuracy assessment."""
    user_content = (
        f"GOAL: {goal}\n\n"
        f"TRAJECTORY:\n{_format_trajectory_steps(trajectory)}\n\n"
        f"FINAL RESULT: {final_result}"
    )
    return [
        {"role": "system", "content": _SYSTEM_PROMPT},
        {"role": "user", "content": user_content},
    ]


def _format_trajectory_steps(trajectory: List[Dict[str, Any]]) -> str:
    """Format trajectory steps for prompt inclusion."""
    if not trajectory:
        return "No trajectory steps provided"

    formatted_steps = []
    for i, step in enumerate(trajectory, 1):
        thought = step.get("thought", "No thought")
        action = step.get("action", "No action")
        observation = step.get("observation", "No observation")

        formatted_steps.append(
            f"Step {i}:\n"
            f"  Thought: {thought}\n"
            f"  Action: {action}\n"
            f"  Observation: {observation}"
        )

    return "\n\n".join(formatted_steps)
