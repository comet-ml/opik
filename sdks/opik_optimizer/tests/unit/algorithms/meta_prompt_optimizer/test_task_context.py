"""MetaPrompt shows training examples as evidence cases, never their labels."""

from __future__ import annotations

from unittest.mock import Mock

from opik_optimizer.algorithms.meta_prompt_optimizer.ops.context_learning_ops import (
    get_task_context,
)


def _metric(dataset_item: dict, llm_output: str) -> float:
    """Exact match on the answer."""
    return 1.0


def test_examples_become_cases_with_selected_input_columns_only() -> None:
    dataset = Mock()
    dataset.get_items.return_value = [
        {
            "id": f"{i}",
            "question": f"What is {i}+{i}?",
            "context": "Math",
            "hint": "Add",
            "answer": f"{2 * i}",
        }
        for i in range(4)
    ]

    context, cases = get_task_context(
        dataset=dataset, metric=_metric, num_examples=3, columns=["question", "hint"]
    )

    assert "{question}" in context and "{hint}" in context
    assert "{context}" not in context and "{answer}" not in context
    assert len(cases) == 3
    assert all(set(case.inputs) == {"question", "hint"} for case in cases)


def test_unknown_columns_fall_back_to_every_input_field() -> None:
    dataset = Mock()
    dataset.get_items.return_value = [{"id": "1", "question": "Q", "answer": "A"}]

    _, cases = get_task_context(
        dataset=dataset, metric=_metric, num_examples=1, columns=["missing"]
    )

    assert [dict(case.inputs) for case in cases] == [{"question": "Q"}]
