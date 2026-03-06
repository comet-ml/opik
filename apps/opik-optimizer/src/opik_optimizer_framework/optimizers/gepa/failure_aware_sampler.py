from __future__ import annotations

import random
from typing import Any, TYPE_CHECKING

if TYPE_CHECKING:
    from gepa.core.data_loader import DataLoader
    from gepa.core.state import GEPAState


class FailureAwareBatchSampler:
    """Batch sampler that balances failed and passed items in minibatches.

    After the first full evaluation, the minibatch is split roughly 50/50
    between failed items (worst-first) and passed items (randomly sampled).
    Including passed items prevents the reflection LLM from over-correcting
    on failures and regressing behaviors that already work.

    Before any failure data exists, sampling is uniform random.

    Also tracks per-item failure streaks and which assertions keep failing,
    so the reflection prompt can include failure history for stuck items.
    """

    def __init__(
        self,
        minibatch_size: int,
        min_failed_per_batch: int = 1,
        failure_threshold: float = 1.0,
        rng: random.Random | None = None,
    ) -> None:
        self.minibatch_size = minibatch_size
        self.min_failed_per_batch = min_failed_per_batch
        self.failure_threshold = failure_threshold
        self.rng = rng if rng is not None else random.Random(0)

        self._item_scores: dict[str, float] = {}
        self._has_full_eval_data: bool = False

        self._idx_to_item_id: dict[int, str] = {}
        self._item_id_to_idx: dict[str, int] = {}

        self._item_failure_streaks: dict[str, int] = {}
        self._item_failed_assertions: dict[str, list[str]] = {}

    def _ensure_mapping(self, loader: DataLoader) -> None:
        if self._idx_to_item_id:
            return
        all_ids = list(loader.all_ids())
        items = loader.fetch(all_ids)
        for idx, item in zip(all_ids, items):
            item_id = str(item.get("id", "")) if isinstance(item, dict) else str(idx)
            self._idx_to_item_id[idx] = item_id
            self._item_id_to_idx[item_id] = idx

    def update_scores(self, per_item_feedback: dict[str, dict[str, Any]]) -> None:
        for item_id, data in per_item_feedback.items():
            self._item_scores[item_id] = data.get("score", 0.0)
        if per_item_feedback:
            self._has_full_eval_data = True

    def update_assertion_failures(self, per_item_feedback: dict[str, dict[str, Any]]) -> None:
        for item_id, data in per_item_feedback.items():
            failed_names: set[str] = set()
            for run in data.get("runs", []):
                for assertion in run.get("assertions", []):
                    if assertion.get("value", 1.0) < 1.0:
                        failed_names.add(assertion.get("name", ""))
            failed_names.discard("")

            if failed_names:
                self._item_failure_streaks[item_id] = self._item_failure_streaks.get(item_id, 0) + 1
                self._item_failed_assertions[item_id] = sorted(failed_names)
            else:
                self._item_failure_streaks.pop(item_id, None)
                self._item_failed_assertions.pop(item_id, None)

    def get_failure_streak(self, item_id: str) -> int:
        return self._item_failure_streaks.get(item_id, 0)

    def get_failed_assertions(self, item_id: str) -> list[str]:
        return self._item_failed_assertions.get(item_id, [])

    def get_stuck_items(self, min_streak: int = 3) -> dict[str, int]:
        return {
            item_id: streak
            for item_id, streak in self._item_failure_streaks.items()
            if streak >= min_streak
        }

    def _score_for_idx(self, idx: int) -> float:
        item_id = self._idx_to_item_id.get(idx, "")
        return self._item_scores.get(item_id, 0.0)

    def next_minibatch_ids(self, loader: DataLoader, state: GEPAState) -> list[int]:
        self._ensure_mapping(loader)
        all_ids = list(loader.all_ids())
        n = min(self.minibatch_size, len(all_ids))

        if not self._has_full_eval_data:
            return self.rng.sample(all_ids, n)

        failed_ids: list[int] = []
        passed_ids: list[int] = []

        for idx in all_ids:
            item_id = self._idx_to_item_id.get(idx, "")
            score = self._item_scores.get(item_id)

            if score is not None and score < self.failure_threshold:
                failed_ids.append(idx)
            else:
                passed_ids.append(idx)

        failed_ids.sort(key=self._score_for_idx)

        selected: list[int] = []
        remaining = n

        # Split slots ~50/50 between failed and passed items.
        # This ensures the reflection LLM sees both what's broken AND what's
        # working, preventing over-correction that regresses passing items.
        n_failed_target = max(self.min_failed_per_batch, remaining // 2)
        n_failed = min(n_failed_target, len(failed_ids), remaining)
        if n_failed > 0:
            selected.extend(failed_ids[:n_failed])
            remaining -= n_failed

        # Fill with passed items to anchor working behaviors
        if remaining > 0 and passed_ids:
            n_passed = min(remaining, len(passed_ids))
            selected.extend(self.rng.sample(passed_ids, n_passed))
            remaining -= n_passed

        # If still remaining (edge case: not enough passed items), fill randomly
        if remaining > 0:
            selected_set = set(selected)
            pool = [idx for idx in all_ids if idx not in selected_set]
            n_fill = min(remaining, len(pool))
            if n_fill > 0:
                selected.extend(self.rng.sample(pool, n_fill))

        return selected
