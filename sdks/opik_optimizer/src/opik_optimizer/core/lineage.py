"""Candidate lineage recorded on trial experiments.

The Opik optimization page reads ``candidate_id``, ``parent_candidate_ids`` and
``step_index`` from each trial experiment's metadata to draw the search tree and
label trials: a candidate something was built on shows as "Passed", one nothing
was built on as "Discarded". Without these keys the UI chains trials in creation
order, so every trial but the last looks "Passed".

A candidate's parents are the prompts the optimizer derived it from. An optimizer
declares them with ``derive_from`` before generating a round of candidates, or
with ``declare_parents`` for one candidate (an Evolutionary crossover child has
two). Optimizers that sample around the original prompt (or don't declare a
source) parent every candidate on the baseline, the root. Re-evaluating the same prompt
reuses its candidate id, so the UI groups those experiments as one candidate.
"""

from __future__ import annotations

import hashlib
import json
import threading
from dataclasses import dataclass
from typing import Any

from ..api_objects import chat_prompt

_PromptLike = chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt]


@dataclass(frozen=True)
class _Candidate:
    candidate_id: str
    step_index: int
    parent_ids: tuple[str, ...]


def prompt_fingerprint(prompt: _PromptLike) -> str:
    prompts = prompt if isinstance(prompt, dict) else {prompt.name: prompt}
    payload = {
        name: {
            "messages": p.get_messages(),
            "tools": p.tools,
            "model": p.model,
            "model_kwargs": p.model_kwargs,
        }
        for name, p in prompts.items()
    }
    encoded = json.dumps(payload, sort_keys=True, default=str).encode()
    return hashlib.sha256(encoded).hexdigest()


class CandidateLineage:
    """Assigns candidate ids and parents to the trial experiments of one run."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.reset()

    def reset(self) -> None:
        with self._lock:
            self._by_fingerprint: dict[str, _Candidate] = {}
            self._root: _Candidate | None = None
            self._sources: tuple[str, ...] = ()
            self._declared: dict[str, tuple[str, ...]] = {}

    def derive_from(self, *sources: _PromptLike) -> None:
        """Declare the prompts the next new candidates are generated from."""
        fingerprints = tuple(prompt_fingerprint(source) for source in sources)
        with self._lock:
            self._sources = fingerprints

    def declare_parents(self, child: _PromptLike, parents: list[_PromptLike]) -> None:
        """Declare the parents of one candidate, ahead of its evaluation."""
        fingerprints = tuple(prompt_fingerprint(parent) for parent in parents)
        with self._lock:
            self._declared[prompt_fingerprint(child)] = fingerprints

    def tag(
        self, experiment_config: dict[str, Any] | None, prompt: _PromptLike
    ) -> dict[str, Any]:
        """Return ``experiment_config`` with this prompt's lineage keys added."""
        config = dict(experiment_config or {})
        if "candidate_id" in config:
            return config
        candidate = self._candidate_for(prompt)
        config["candidate_id"] = candidate.candidate_id
        config["step_index"] = candidate.step_index
        config["parent_candidate_ids"] = list(candidate.parent_ids)
        return config

    def _candidate_for(self, prompt: _PromptLike) -> _Candidate:
        fingerprint = prompt_fingerprint(prompt)
        with self._lock:
            existing = self._by_fingerprint.get(fingerprint)
            if existing is not None:
                return existing
            if self._root is None:
                candidate = _Candidate("baseline", 0, ())
                self._root = candidate
            else:
                root = self._root
                parents = self._known_parents(
                    self._declared.pop(fingerprint, ()) or self._sources
                ) or [root]
                candidate = _Candidate(
                    f"candidate-{len(self._by_fingerprint)}",
                    1 + max(parent.step_index for parent in parents),
                    tuple(parent.candidate_id for parent in parents),
                )
            self._by_fingerprint[fingerprint] = candidate
            return candidate

    def _known_parents(self, fingerprints: tuple[str, ...]) -> list[_Candidate]:
        # A parent that was never evaluated has no trial to point at.
        found: dict[str, _Candidate] = {}
        for fingerprint in fingerprints:
            parent = self._by_fingerprint.get(fingerprint)
            if parent is not None:
                found.setdefault(parent.candidate_id, parent)
        return list(found.values())
