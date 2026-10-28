"""Candidate lineage recorded on trial experiments.

The Opik optimization page reads ``candidate_id``, ``parent_candidate_ids`` and
``step_index`` from each trial experiment's metadata to draw the search tree and
label trials: a candidate something was built on shows as "Passed", one nothing
was built on as "Discarded". Without these keys the UI chains trials in creation
order, so every trial but the last looks "Passed".

A candidate's parent is the prompt the optimizer derived it from, which the
optimizer declares with ``derive_from`` before generating a round of candidates.
Optimizers that sample around the original prompt (or don't declare a source)
parent every candidate on the baseline, the root. Re-evaluating the same prompt
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


def _fingerprint(prompt: _PromptLike) -> str:
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
            self._source: str | None = None

    def derive_from(self, source: _PromptLike) -> None:
        """Declare the prompt the next new candidates are generated from."""
        fingerprint = _fingerprint(source)
        with self._lock:
            self._source = fingerprint

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
        fingerprint = _fingerprint(prompt)
        with self._lock:
            existing = self._by_fingerprint.get(fingerprint)
            if existing is not None:
                return existing
            if self._root is None:
                candidate = _Candidate("baseline", 0, ())
                self._root = candidate
            else:
                parent = (
                    self._by_fingerprint.get(self._source) if self._source else None
                ) or self._root
                candidate = _Candidate(
                    f"candidate-{len(self._by_fingerprint)}",
                    parent.step_index + 1,
                    (parent.candidate_id,),
                )
            self._by_fingerprint[fingerprint] = candidate
            return candidate
