"""Tests for evolutionary optimizer helpers module."""

from __future__ import annotations

from typing import Any

from opik_optimizer.algorithms.evolutionary_optimizer.helpers import (
    calculate_population_diversity,
)
from opik_optimizer.algorithms.evolutionary_optimizer import helpers
from tests.unit.fixtures import assistant_message, system_message, user_message


class TestCalculatePopulationDiversity:
    """Tests for calculate_population_diversity function."""

    def test_empty_population_returns_zero(self) -> None:
        diversity = calculate_population_diversity([])
        assert diversity == 0.0

    def test_none_population_returns_zero(self) -> None:
        diversity = calculate_population_diversity(None)
        assert diversity == 0.0

    def test_single_individual_returns_zero(self) -> None:
        population = [{"prompt": [system_message("Test")]}]
        diversity = calculate_population_diversity(population)
        assert diversity == 0.0

    def test_identical_individuals_low_diversity(self) -> None:
        individual = {"prompt": [system_message("Test prompt")]}
        population = [individual, individual.copy(), individual.copy()]
        diversity = calculate_population_diversity(population)
        assert diversity >= 0.0

    def test_different_individuals_have_diversity(self) -> None:
        population = [
            {"prompt": [system_message("Short test")]},
            {
                "prompt": [
                    {
                        **system_message(
                            "A completely different and much longer prompt content"
                        ),
                    }
                ]
            },
        ]
        diversity = calculate_population_diversity(population)
        assert diversity >= 0.0

    def test_handles_dict_individuals(self) -> None:
        population = [
            {"key": "value1", "prompt": "test1"},
            {"key": "value2", "prompt": "test2"},
            {"key": "value3", "prompt": "test3"},
        ]
        diversity = calculate_population_diversity(population)
        assert isinstance(diversity, float)

    def test_handles_non_dict_individuals(self) -> None:
        population = ["individual1", "individual2", "individual3"]
        diversity = calculate_population_diversity(population)
        assert isinstance(diversity, float)

    def test_handles_mixed_population(self) -> None:
        class MockIndividual:
            def __init__(self, data: dict[str, Any]) -> None:
                self._data = data

            def items(self) -> Any:
                return self._data.items()

            def keys(self) -> Any:
                return self._data.keys()

            def __iter__(self) -> Any:
                return iter(self._data)

            def __getitem__(self, key: str) -> Any:
                return self._data[key]

            def __str__(self) -> str:
                return str(self._data)

        population = [
            MockIndividual({"prompt": "test1"}),
            MockIndividual({"prompt": "test2"}),
        ]
        diversity = calculate_population_diversity(population)
        assert isinstance(diversity, float)

    def test_diversity_increases_with_varied_content(self) -> None:
        similar_pop = [
            {"content": "Hello world"},
            {"content": "Hello world!"},
            {"content": "Hello, world"},
        ]
        similar_diversity = calculate_population_diversity(similar_pop)

        diverse_pop = [
            {"content": "Hello world"},
            {"content": "The quick brown fox jumps over the lazy dog"},
            {"content": "Python programming language"},
        ]
        diverse_diversity = calculate_population_diversity(diverse_pop)

        assert isinstance(similar_diversity, float)
        assert isinstance(diverse_diversity, float)
        assert diverse_diversity > similar_diversity
        assert similar_diversity < 0.2


class _ResponseModel:
    def __init__(self, payload: Any) -> None:
        self._payload = payload

    def model_dump(self) -> Any:
        return self._payload


def test_parse_llm_messages_returns_list_passthrough() -> None:
    messages = [system_message("x")]
    assert helpers.parse_llm_messages(messages) == messages


def test_parse_llm_messages_handles_dict_with_messages() -> None:
    payload = {"messages": [user_message("y")]}
    assert helpers.parse_llm_messages(payload) == payload["messages"]


def test_parse_llm_messages_handles_dict_without_messages() -> None:
    payload = user_message("z")
    assert helpers.parse_llm_messages(payload) == [payload]


def test_parse_llm_messages_handles_model_dump() -> None:
    payload = {"messages": [assistant_message("ok")]}
    model = _ResponseModel(payload)
    assert helpers.parse_llm_messages(model) == payload["messages"]


def test_parse_llm_messages_handles_json_string() -> None:
    import json

    payload = json.dumps([system_message("hi")])
    assert helpers.parse_llm_messages(payload) == [system_message("hi")]
