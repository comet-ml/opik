import pytest
from typing import Any
from opik_optimizer.algorithms.evolutionary_optimizer.ops import mutation_ops
import opik_optimizer


def test_semantic_mutation_invalid_json_response(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def fake_call_model(
        *,
        messages: list[dict[str, str]],
        is_reasoning: bool,
        model: str,
        model_parameters: dict[str, Any],
    ) -> str:
        # Model responded with a Python repr instead of strict JSON
        return "[{'role': 'system', 'content': 'Provide a brief and direct answer to the question.'}, {'role': 'user', 'content': '{question}'}]"

    monkeypatch.setattr(
        "opik_optimizer._llm_calls.call_model",
        fake_call_model,
    )

    monkeypatch.setattr(
        "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.random",
        lambda: 0.5,
    )
    monkeypatch.setattr(
        "opik_optimizer.algorithms.evolutionary_optimizer.helpers.get_task_description_for_llm",
        lambda initial_prompt: "Summarize task",
    )
    monkeypatch.setattr(
        "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.choice",
        lambda seq: seq[0],
    )

    captured: dict[str, object] = {}

    def fake_display_error(message: str, verbose: int = 1) -> None:
        captured["message"] = message
        captured["verbose"] = verbose

    monkeypatch.setattr(
        "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.reporting.display_error",
        fake_display_error,
    )

    original_prompt = opik_optimizer.ChatPrompt(
        messages=[
            {"role": "system", "content": "Provide factual answers."},
            {"role": "user", "content": "What is the capital of France?"},
        ]
    )

    result = mutation_ops._semantic_mutation(
        prompt=original_prompt,
        initial_prompt=original_prompt,
        output_style_guidance="Keep answers brief.",
        model="openai/gpt-5-mini",
        model_parameters={},
        verbose=1,
    )

    assert result is not original_prompt
    assert captured == {}
    assert result.get_messages() == [
        {
            "role": "system",
            "content": "Provide a brief and direct answer to the question.",
        },
        {"role": "user", "content": "{question}"},
    ]
