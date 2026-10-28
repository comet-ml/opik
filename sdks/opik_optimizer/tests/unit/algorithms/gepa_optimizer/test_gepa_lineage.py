"""GEPA candidates are logged as children of the programs GEPA built them from."""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any

from opik_optimizer import ChatPrompt
from opik_optimizer.algorithms.gepa_optimizer.adapter import LineageCallback
from opik_optimizer.core.lineage import CandidateLineage


def _prompt(text: str) -> dict[str, ChatPrompt]:
    return {"p": ChatPrompt(name="p", system=text, user="{question}")}


def _callback(lineage: CandidateLineage) -> LineageCallback:
    adapter: Any = SimpleNamespace(
        prompts_for=lambda candidate: _prompt(candidate["system"]),
        _optimizer=SimpleNamespace(_lineage=lineage),
    )
    return LineageCallback(adapter)


def _start(parent_ids: list[int]) -> dict[str, Any]:
    return {"candidate_idx": None, "is_seed_candidate": False, "parent_ids": parent_ids}


def test_a_merged_candidate_records_both_parents() -> None:
    lineage = CandidateLineage()
    callback = _callback(lineage)
    for idx, text in enumerate(["seed", "left", "right"]):
        callback.on_valset_evaluated(
            {"candidate_idx": idx, "candidate": {"system": text}}
        )
        lineage.tag(None, _prompt(text))

    callback.on_evaluation_start(_start([1, 2]))
    merged = lineage.tag(None, _prompt("merged"))

    left, right = (
        lineage.tag(None, _prompt(t))["candidate_id"] for t in ("left", "right")
    )
    assert merged["parent_candidate_ids"] == [left, right]


def test_rescoring_a_known_program_keeps_the_current_parents() -> None:
    lineage = CandidateLineage()
    callback = _callback(lineage)
    for idx, text in enumerate(["seed", "parent"]):
        callback.on_valset_evaluated(
            {"candidate_idx": idx, "candidate": {"system": text}}
        )
        lineage.tag(None, _prompt(text))
    callback.on_evaluation_start(_start([1]))

    # GEPA re-scores the parent on a minibatch (candidate_idx set) before proposing.
    callback.on_evaluation_start(
        {"candidate_idx": 0, "is_seed_candidate": False, "parent_ids": []}
    )
    child = lineage.tag(None, _prompt("child"))

    parent = lineage.tag(None, _prompt("parent"))["candidate_id"]
    assert child["parent_candidate_ids"] == [parent]
