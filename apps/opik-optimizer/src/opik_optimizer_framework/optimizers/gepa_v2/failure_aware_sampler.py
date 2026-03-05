from __future__ import annotations

import random
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from gepa.core.data_loader import DataLoader
    from gepa.core.state import GEPAState


class FailureAwareBatchSampler:
    """Batch sampler that prioritizes failed and unseen items in minibatches.

    After the first full evaluation, items that scored below `failure_threshold`
    are guaranteed `min_failed_per_batch` slots.  Items never seen in any
    minibatch get `min_unseen_per_batch` slots.  Remaining slots are filled
    randomly.  Before any failure data exists, sampling is uniform random.
    """

    def __init__(
        self,
        minibatch_size: int,
        min_failed_per_batch: int = 1,
        min_unseen_per_batch: int = 0,
        failure_threshold: float = 1.0,
        rng: random.Random | None = None,
    ) -> None:
        self.minibatch_size = minibatch_size
        self.min_failed_per_batch = min_failed_per_batch
        self.min_unseen_per_batch = min_unseen_per_batch
        self.failure_threshold = failure_threshold
        self.rng = rng if rng is not None else random.Random(0)

        self._item_scores: dict[str, float] = {}
        self._seen_item_ids: set[str] = set()
        self._has_full_eval_data: bool = False

        self._idx_to_item_id: dict[int, str] = {}
        self._item_id_to_idx: dict[str, int] = {}

    def _ensure_mapping(self, loader: DataLoader) -> None:
        if self._idx_to_item_id:
            return
        all_ids = list(loader.all_ids())
        items = loader.fetch(all_ids)
        for idx, item in zip(all_ids, items):
            item_id = str(item.get("id", "")) if isinstance(item, dict) else str(idx)
            self._idx_to_item_id[idx] = item_id
            self._item_id_to_idx[item_id] = idx

    def update_scores(self, per_item_feedback: dict[str, dict[str, float]]) -> None:
        for item_id, data in per_item_feedback.items():
            self._item_scores[item_id] = data.get("score", 0.0)
        if per_item_feedback:
            self._has_full_eval_data = True

    def mark_seen(self, item_ids: list[str]) -> None:
        self._seen_item_ids.update(item_ids)

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
        unseen_ids: list[int] = []
        other_ids: list[int] = []

        for idx in all_ids:
            item_id = self._idx_to_item_id.get(idx, "")
            score = self._item_scores.get(item_id)

            if score is not None and score < self.failure_threshold:
                failed_ids.append(idx)
            elif item_id not in self._seen_item_ids:
                unseen_ids.append(idx)
            else:
                other_ids.append(idx)

        # Sort failed items by score ascending (worst first)
        failed_ids.sort(key=self._score_for_idx)

        selected: list[int] = []
        remaining = n

        n_failed = min(self.min_failed_per_batch, len(failed_ids), remaining)
        if n_failed > 0:
            selected.extend(failed_ids[:n_failed])
            remaining -= n_failed

        available_unseen = [u for u in unseen_ids if u not in selected]
        n_unseen = min(self.min_unseen_per_batch, len(available_unseen), remaining)
        if n_unseen > 0:
            selected.extend(self.rng.sample(available_unseen, n_unseen))
            remaining -= n_unseen

        if remaining > 0:
            selected_set = set(selected)
            pool = [idx for idx in all_ids if idx not in selected_set]
            n_fill = min(remaining, len(pool))
            if n_fill > 0:
                selected.extend(self.rng.sample(pool, n_fill))

        return selected
