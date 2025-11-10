from typing import Any

import pytest

pytest.importorskip("gepa", reason="gepa package required for GEPA optimizer tests")

from opik_optimizer.gepa_optimizer import GepaOptimizer  # noqa: E402
from opik_optimizer.optimization_config.chat_prompt import ChatPrompt  # noqa: E402


def test_extract_system_text_prefers_system_message() -> None:
    opt = GepaOptimizer(model="openai/gpt-4o-mini")
    prompt = ChatPrompt(system="You are system.", user="{q}")
    got = opt._extract_system_text(prompt)
    assert got == "You are system."


def test_extract_system_text_falls_back_to_user() -> None:
    opt = GepaOptimizer(model="openai/gpt-4o-mini")
    prompt = ChatPrompt(user="{q}")
    got = opt._extract_system_text(prompt)
    assert "helpful assistant" in got.lower()


def test_infer_dataset_keys_heuristics() -> None:
    class DummyDataset:
        name = "dummy"

        def get_items(self, *args: Any, **kwargs: Any) -> list[dict[str, Any]]:
            return [
                {"id": "1", "question": "Q?", "answer": "A", "metadata": {}},
            ]

    opt = GepaOptimizer(model="openai/gpt-4o-mini")
    inp, out = opt._infer_dataset_keys(DummyDataset())
    # Should pick a non-output as input and one of known output keys
    assert out in ("label", "answer", "output", "expected_output")
    assert inp in ("question", "text")


def test_build_data_insts_mapping() -> None:
    items: list[dict[str, Any]] = [
        {"question": "Q1", "answer": "A1", "metadata": {"context": "C1"}},
        {"question": "Q2", "answer": "A2"},
    ]
    opt = GepaOptimizer(model="openai/gpt-4o-mini")
    converted = opt._build_data_insts(items, input_key="question", output_key="answer")
    assert len(converted) == 2
    assert converted[0].input_text == "Q1"
    assert converted[0].answer == "A1"
    assert converted[0].additional_context.get("context") == "C1"
    assert converted[1].additional_context == {}
