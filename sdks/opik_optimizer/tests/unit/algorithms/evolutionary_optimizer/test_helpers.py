"""Tests for evolutionary optimizer helpers module."""

from __future__ import annotations

from typing import Any

from opik_optimizer.algorithms.evolutionary_optimizer import helpers
from tests.unit.fixtures import assistant_message, system_message, user_message


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
