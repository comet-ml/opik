import pytest

from opik_optimizer.evolutionary_optimizer.mutation_ops import MutationOps
from opik_optimizer.optimization_config import chat_prompt


def test_semantic_mutation_invalid_json_response(monkeypatch: pytest.MonkeyPatch) -> None:
    mutation_ops = MutationOps()
    mutation_ops.verbose = 1
    mutation_ops.output_style_guidance = "Keep answers brief."
    mutation_ops._get_task_description_for_llm = lambda initial_prompt: "Summarize task"

    def fake_call_model(*, messages, is_reasoning):
        # Model responded with a Python repr instead of strict JSON
        return "[{'role': 'system', 'content': 'Provide a brief and direct answer to the question.'}, {'role': 'user', 'content': '{question}'}]"

    mutation_ops._call_model = fake_call_model

    monkeypatch.setattr(
        "opik_optimizer.evolutionary_optimizer.mutation_ops.random.random",
        lambda: 0.5,
    )
    monkeypatch.setattr(
        "opik_optimizer.evolutionary_optimizer.mutation_ops.random.choice",
        lambda seq: seq[0],
    )

    captured: dict[str, object] = {}

    def fake_display_error(message: str, verbose: int = 1) -> None:
        captured["message"] = message
        captured["verbose"] = verbose

    monkeypatch.setattr(
        "opik_optimizer.evolutionary_optimizer.mutation_ops.reporting.display_error",
        fake_display_error,
    )

    original_prompt = chat_prompt.ChatPrompt(
        messages=[
            {"role": "system", "content": "Provide factual answers."},
            {"role": "user", "content": "What is the capital of France?"},
        ]
    )

    result = mutation_ops._semantic_mutation(original_prompt, original_prompt)

    assert result is not original_prompt
    assert captured == {}
    assert result.get_messages() == [
        {
            "role": "system",
            "content": "Provide a brief and direct answer to the question.",
        },
        {"role": "user", "content": "{question}"},
    ]
