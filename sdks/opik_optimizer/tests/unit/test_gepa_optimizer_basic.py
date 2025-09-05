from typing import Any, Dict, List

import pytest

from opik_optimizer.gepa_optimizer import GepaOptimizer
from opik_optimizer.optimization_config.chat_prompt import ChatPrompt


def test_extract_system_text_prefers_system_message() -> None:
    opt = GepaOptimizer(model="openai/gpt-4o-mini", reflection_model="openai/gpt-4o")
    prompt = ChatPrompt(system="You are system.", user="{q}")
    got = opt._extract_system_text(prompt)
    assert got == "You are system."


def test_extract_system_text_falls_back_to_user() -> None:
    opt = GepaOptimizer(model="openai/gpt-4o-mini", reflection_model="openai/gpt-4o")
    prompt = ChatPrompt(user="{q}")
    got = opt._extract_system_text(prompt)
    assert "helpful assistant" in got.lower()


def test_infer_dataset_keys_heuristics() -> None:
    class DummyDataset:
        name = "dummy"

        def get_items(self, *args: Any, **kwargs: Any) -> List[Dict[str, Any]]:
            return [
                {"id": "1", "question": "Q?", "answer": "A", "metadata": {}},
            ]

    opt = GepaOptimizer(model="openai/gpt-4o-mini", reflection_model="openai/gpt-4o")
    inp, out = opt._infer_dataset_keys(DummyDataset())
    # Should pick a non-output as input and one of known output keys
    assert out in ("label", "answer", "output", "expected_output")
    assert inp in ("question", "text")


def test_to_gepa_default_datainst_mapping() -> None:
    items = [
        {"question": "Q1", "answer": "A1", "metadata": {"context": "C1"}},
        {"question": "Q2", "answer": "A2"},
    ]
    opt = GepaOptimizer(model="openai/gpt-4o-mini", reflection_model="openai/gpt-4o")
    converted = opt._to_gepa_default_datainst(items, input_key="question", output_key="answer")
    assert len(converted) == 2
    assert converted[0]["input"] == "Q1"
    assert converted[0]["answer"] == "A1"
    assert converted[0]["additional_context"].get("context") == "C1"
    assert converted[1]["additional_context"] == {}

