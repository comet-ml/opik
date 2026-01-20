"""Shared helpers for evolutionary mutation ops unit tests."""

from __future__ import annotations

from typing import Any

import pytest

_MUTATION_OPS_MODULE = "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops"


def force_random(
    monkeypatch: pytest.MonkeyPatch,
    *,
    random_value: float,
    randint_value: int | None = None,
    sample_value: list[int] | None = None,
    choice_value: Any | None = None,
) -> None:
    monkeypatch.setattr(f"{_MUTATION_OPS_MODULE}.random.random", lambda: random_value)
    if randint_value is not None:
        monkeypatch.setattr(
            f"{_MUTATION_OPS_MODULE}.random.randint",
            lambda _a, _b: randint_value,
        )
    if sample_value is not None:
        monkeypatch.setattr(
            f"{_MUTATION_OPS_MODULE}.random.sample",
            lambda _seq, _k: sample_value,
        )
    if choice_value is not None:
        monkeypatch.setattr(
            f"{_MUTATION_OPS_MODULE}.random.choice",
            lambda _seq: choice_value,
        )


def patch_get_synonym(monkeypatch: pytest.MonkeyPatch, *, return_value: str) -> None:
    def fake_get_synonym(**_kwargs: Any) -> str:
        return return_value

    monkeypatch.setattr(f"{_MUTATION_OPS_MODULE}._get_synonym", fake_get_synonym)


def patch_modify_phrase(monkeypatch: pytest.MonkeyPatch, *, return_value: str) -> None:
    def fake_modify_phrase(**_kwargs: Any) -> str:
        return return_value

    monkeypatch.setattr(f"{_MUTATION_OPS_MODULE}._modify_phrase", fake_modify_phrase)

