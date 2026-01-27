from __future__ import annotations

from opik_optimizer.api_objects import chat_prompt
from opik_optimizer.algorithms.gepa_optimizer.ops import candidate_ops


def test_gepa_seed_candidate_respects_allowed_roles() -> None:
    prompt = chat_prompt.ChatPrompt(
        name="p",
        messages=[
            {"role": "system", "content": "System"},
            {"role": "user", "content": "{question}"},
        ],
    )
    seed = candidate_ops.build_seed_candidate(
        optimizable_prompts={"p": prompt},
        allowed_roles={"system"},
    )
    assert "p_system_0" in seed
    assert "p_user_1" not in seed


def test_gepa_rebuild_respects_allowed_roles() -> None:
    prompt = chat_prompt.ChatPrompt(
        name="p",
        messages=[
            {"role": "system", "content": "System"},
            {"role": "user", "content": "{question}"},
        ],
    )
    candidate = {
        "p_system_0": "System v2",
        "p_user_1": "mutated user",
    }
    rebuilt = candidate_ops.rebuild_prompts_from_candidate(
        base_prompts={"p": prompt},
        candidate=candidate,
        allowed_roles={"system"},
    )
    rebuilt_messages = rebuilt["p"].get_messages()
    assert rebuilt_messages[0]["content"] == "System v2"
    assert rebuilt_messages[1]["content"] == "{question}"
