"""Shared helpers for evolutionary mutation ops unit tests."""

from __future__ import annotations

from typing import Any
from collections.abc import Sequence

import pytest

_MUTATION_OPS_MODULE = (
    "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops"
)


class _StubRng:
    def __init__(
        self,
        *,
        random_value: float,
        randint_value: int | None = None,
        sample_value: list[int] | None = None,
        choice_value: Any | None = None,
    ) -> None:
        self._random_value = random_value
        self._randint_value = randint_value
        self._sample_value = sample_value
        self._choice_value = choice_value

    def random(self) -> float:
        return self._random_value

    def randint(self, a: int, b: int) -> int:
        if self._randint_value is None:
            return a
        return max(a, min(self._randint_value, b))

    def sample(self, seq: Sequence[Any], k: int) -> list[Any]:
        if self._sample_value is not None:
            return list(self._sample_value)
        return list(seq)[:k]

    def choice(self, seq: Sequence[Any]) -> Any:
        if self._choice_value is not None:
            return self._choice_value
        return seq[0]

    def shuffle(self, seq: list[Any]) -> None:
        return None


def force_random(
    monkeypatch: pytest.MonkeyPatch,
    *,
    random_value: float,
    randint_value: int | None = None,
    sample_value: list[int] | None = None,
    choice_value: Any | None = None,
) -> _StubRng:
    _ = monkeypatch  # preserve signature for existing tests
    return _StubRng(
        random_value=random_value,
        randint_value=randint_value,
        sample_value=sample_value,
        choice_value=choice_value,
    )


def patch_get_synonym(monkeypatch: pytest.MonkeyPatch, *, return_value: str) -> None:
    def fake_get_synonym(**_kwargs: Any) -> str:
        return return_value

    monkeypatch.setattr(f"{_MUTATION_OPS_MODULE}._get_synonym", fake_get_synonym)


def patch_modify_phrase(monkeypatch: pytest.MonkeyPatch, *, return_value: str) -> None:
    def fake_modify_phrase(**_kwargs: Any) -> str:
        return return_value

    monkeypatch.setattr(f"{_MUTATION_OPS_MODULE}._modify_phrase", fake_modify_phrase)
