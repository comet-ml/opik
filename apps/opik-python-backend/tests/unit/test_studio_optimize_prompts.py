"""Which prompt roles a Studio run makes optimizable (OPIK-7510).

The algorithms are stable on one shape: instructions in the system message,
template variables in the user message. When a system message exists we scope
optimization to it, so the reflection LM is never handed the message holding the
user's variables. When there is no system message we must still widen to the
roles that are present — optimizing an empty set leaves GEPA zero editable
components and it divides by zero while round-robin selecting one.
"""

from unittest.mock import MagicMock

from opik_backend.studio.helpers import run_optimization


def _optimize_prompts_for(roles: list[str]):
    """Run a Studio optimization over a prompt with these message roles."""
    optimizer = MagicMock()
    optimizer.optimize_prompt.return_value = MagicMock(score=1.0, initial_score=None)

    prompt = MagicMock()
    prompt.get_messages.return_value = [
        {"role": role, "content": f"{role} content"} for role in roles
    ]

    run_optimization(
        optimizer=optimizer,
        optimization_id="opt-1",
        prompt=prompt,
        dataset=MagicMock(),
        metric_fn=lambda *_args, **_kwargs: 0.0,
    )

    assert optimizer.optimize_prompt.call_count == 1
    return optimizer.optimize_prompt.call_args.kwargs["optimize_prompts"]


class TestOptimizePromptsRoleScoping:
    def test_system_present_optimizes_only_system(self):
        """The Studio default shape — variables in `user` stay untouched."""
        assert _optimize_prompts_for(["system", "user"]) == ["system"]

    def test_system_present_with_assistant_still_only_system(self):
        assert _optimize_prompts_for(["system", "user", "assistant"]) == ["system"]

    def test_user_only_widens_to_user(self):
        """Preserves the divide-by-zero guard for system-less prompts."""
        assert _optimize_prompts_for(["user"]) == ["user"]

    def test_user_and_assistant_widen_to_both(self):
        assert _optimize_prompts_for(["user", "assistant"]) == ["assistant", "user"]

    def test_no_recognised_roles_falls_back_to_system(self):
        assert _optimize_prompts_for([]) == "system"
