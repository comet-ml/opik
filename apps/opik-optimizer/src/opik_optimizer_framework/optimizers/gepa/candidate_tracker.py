from __future__ import annotations

import logging
from collections.abc import Sequence
from typing import Any, TYPE_CHECKING

if TYPE_CHECKING:
    from opik_optimizer_framework.types import TrialResult

logger = logging.getLogger(__name__)


def _candidate_key(candidate: dict[str, str]) -> str:
    """Deterministic string key for a GEPA candidate dict."""
    import json
    return json.dumps(candidate, sort_keys=True)


class CandidateTracker:
    """Tracks candidate identity, parent lineage, and GEPA-to-framework ID mapping.

    Manages the lifecycle of candidate IDs across GEPA iterations:
    - Maps GEPA's integer candidate indices to framework UUIDs
    - Resolves parent candidate IDs using a priority chain
    - Determines evaluation purpose from callback metadata
    """

    def __init__(self) -> None:
        self._known_candidates: dict[str, str] = {}
        self._candidate_parents: dict[str, list[str]] = {}
        self._baseline_candidate_id: str | None = None
        self._seed_candidate_key: str | None = None
        self._gepa_idx_to_candidate_id: dict[int, str] = {}

        self._current_step: int = -1
        self._selected_parent_id: str | None = None

        self._pending_eval_parent_ids: list[int] | None = None
        self._pending_eval_capture_traces: bool | None = None
        self._pending_eval_candidate_idx: int | None = None
        self._pending_merge_parent_ids: list[int] | None = None

    def register_baseline(
        self,
        seed_candidate: dict[str, str],
        baseline_candidate_id: str,
    ) -> None:
        """Pre-seed tracking so GEPA's initialization reuses the baseline's candidate_id."""
        key = _candidate_key(seed_candidate)
        self._known_candidates[key] = baseline_candidate_id
        self._candidate_parents[baseline_candidate_id] = []
        self._baseline_candidate_id = baseline_candidate_id
        self._seed_candidate_key = key
        self._gepa_idx_to_candidate_id[0] = baseline_candidate_id

    def on_new_step(self, iteration: int) -> None:
        self._current_step = iteration
        self._selected_parent_id = None
        self._pending_merge_parent_ids = None
        self._pending_eval_parent_ids = None
        self._pending_eval_capture_traces = None
        self._pending_eval_candidate_idx = None

    def on_candidate_selected(self, candidate_idx: int) -> None:
        self._selected_parent_id = self._gepa_idx_to_candidate_id.get(
            candidate_idx
        )

    def on_evaluation_start(
        self,
        candidate_idx: int | None,
        parent_ids: Sequence[int],
        capture_traces: bool,
    ) -> None:
        self._pending_eval_candidate_idx = candidate_idx
        self._pending_eval_parent_ids = list(parent_ids)
        self._pending_eval_capture_traces = capture_traces

    def on_valset_evaluated(
        self, candidate_idx: int, candidate: dict[str, str],
    ) -> None:
        key = _candidate_key(candidate)
        fw_id = self._known_candidates.get(key)
        if fw_id is not None:
            self._gepa_idx_to_candidate_id[candidate_idx] = fw_id

    def on_merge_accepted(self, parent_ids: Sequence[int]) -> None:
        self._pending_merge_parent_ids = list(parent_ids)

    def get_pending_capture_traces(self) -> bool | None:
        value = self._pending_eval_capture_traces
        return value

    def clear_pending_eval(self) -> None:
        self._pending_eval_parent_ids = None
        self._pending_eval_capture_traces = None
        self._pending_eval_candidate_idx = None

    def resolve_parent_ids(self, key: str) -> list[str]:
        """Resolve parent candidate IDs using callback metadata and tracking maps.

        Priority:
        1. Merge parents (from on_merge_accepted)
        2. Pre-eval parents (from on_evaluation_start, resolved via gepa_idx map)
        3. Persistent parents (from _candidate_parents for known candidates)
        4. Selected parent (from on_candidate_selected)
        5. Baseline fallback
        """
        if self._pending_merge_parent_ids is not None:
            resolved = [
                self._gepa_idx_to_candidate_id[idx]
                for idx in self._pending_merge_parent_ids
                if idx in self._gepa_idx_to_candidate_id
            ]
            self._pending_merge_parent_ids = None
            if resolved:
                return resolved

        if self._pending_eval_parent_ids is not None:
            resolved = [
                self._gepa_idx_to_candidate_id[idx]
                for idx in self._pending_eval_parent_ids
                if idx in self._gepa_idx_to_candidate_id
            ]
            if resolved:
                return resolved

        known_id = self._known_candidates.get(key)
        if known_id is not None and known_id in self._candidate_parents:
            return self._candidate_parents[known_id]

        if self._selected_parent_id is not None:
            return [self._selected_parent_id]

        if self._baseline_candidate_id is not None:
            return [self._baseline_candidate_id]

        return []

    def record_trial(
        self,
        key: str,
        trial: TrialResult,
        parent_candidate_ids: list[str],
        capture_traces: bool,
    ) -> None:
        """Update candidate tracking maps after a successful evaluation."""
        is_new = trial.candidate_id not in self._candidate_parents
        self._known_candidates[key] = trial.candidate_id
        if is_new:
            self._candidate_parents[trial.candidate_id] = parent_candidate_ids

        if capture_traces:
            self._selected_parent_id = trial.candidate_id

        logger.debug(
            "[tracker.record_trial] score=%.4f candidate_id=%s "
            "is_new=%s parents=%s",
            trial.score,
            trial.candidate_id[:8],
            is_new,
            self._candidate_parents.get(trial.candidate_id, []),
        )

    def get_existing_candidate_id(self, key: str) -> str | None:
        return self._known_candidates.get(key)
