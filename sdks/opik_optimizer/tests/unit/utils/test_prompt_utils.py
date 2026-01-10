from __future__ import annotations

from collections.abc import Callable
from typing import cast

import pytest

from opik_optimizer.utils import prompt_utils


def test_resolve_prompt_set_returns_defaults() -> None:
    defaults = {"a": "A", "b": "B"}
    resolved = prompt_utils.resolve_prompt_set(defaults)
    assert resolved == defaults
    assert resolved is not defaults


def test_resolve_prompt_set_applies_overrides() -> None:
    defaults = {"a": "A", "b": "B"}
    resolved = prompt_utils.resolve_prompt_set(defaults, {"b": "B2"})
    assert resolved["a"] == "A"
    assert resolved["b"] == "B2"


def test_resolve_prompt_set_rejects_unknown_keys() -> None:
    defaults = {"a": "A"}
    with pytest.raises(ValueError, match="Unknown prompt override keys"):
        prompt_utils.resolve_prompt_set(defaults, {"unknown": "X"})


def test_resolve_prompt_set_allows_unknown_keys() -> None:
    defaults = {"a": "A"}
    resolved = prompt_utils.resolve_prompt_set(
        defaults, {"unknown": "X"}, allow_unknown=True
    )
    assert resolved["unknown"] == "X"


def test_resolve_prompt_set_factory_runs_last() -> None:
    defaults = {"a": "A", "b": "B"}

    def factory(prompt_set: dict[str, str]) -> dict[str, str]:
        prompt_set["b"] = prompt_set["b"] + "!"
        return prompt_set

    resolved = prompt_utils.resolve_prompt_set(
        defaults,
        {"b": "B2"},
        factory=cast(Callable[[dict[str, str]], dict[str, str]], factory),
    )
    assert resolved["b"] == "B2!"


def test_resolve_prompt_value_uses_fallback() -> None:
    assert (
        prompt_utils.resolve_prompt_value(None, key="a", fallback="A") == "A"
    )


def test_resolve_prompt_value_uses_prompt_set() -> None:
    prompt_set = {"a": "A2"}
    assert (
        prompt_utils.resolve_prompt_value(prompt_set, key="a", fallback="A") == "A2"
    )
