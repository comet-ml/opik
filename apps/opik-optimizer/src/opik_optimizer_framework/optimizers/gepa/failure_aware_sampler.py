from __future__ import annotations

import random
from typing import Any, TYPE_CHECKING

if TYPE_CHECKING:
    from gepa.core.data_loader import DataLoader
    from gepa.core.state import GEPAState


class FailureAwareBatchSampler:
    """Batch sampler that balances failed and passed items in minibatches.

    After the first full evaluation, items are split into *failed* and *passed*.
    Failed items are split into a "top" tier (worst ``top_failed_fraction`` by
    assertion failure count) and the rest.  The minibatch draws randomly from
    the top tier first, then the rest of failed, then passed items (~50/50
    failed/passed split).

    Before any evaluation data exists, sampling is uniform random.
    """

    def __init__(
        self,
        minibatch_size: int,
        min_failed_per_batch: int = 1,
        top_failed_fraction: float = 0.5,
        rng: random.Random | None = None,
    ) -> None:
        self.minibatch_size = minibatch_size
        self.min_failed_per_batch = min_failed_per_batch
        self.top_failed_fraction = top_failed_fraction
        self.rng = rng if rng is not None else random.Random(0)

        self._item_scores: dict[str, float] = {}
        self._has_full_eval_data: bool = False

        self._idx_to_item_id: dict[int, str] = {}
        self._item_id_to_idx: dict[str, int] = {}

        self._item_failure_streaks: dict[str, int] = {}
        self._item_failed_assertions: dict[str, list[str]] = {}
        self._assertion_total_failures: dict[str, int] = {}
        self._assertion_total_evals: dict[str, int] = {}

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
            all_names: set[str] = set()
            failed_names: set[str] = set()
            for run in data.get("runs", []):
                for assertion in run.get("assertions", []):
                    name = assertion.get("name", "")
                    if name:
                        all_names.add(name)
                    if assertion.get("value", 1.0) < 1.0 and name:
                        failed_names.add(name)

            for name in all_names:
                self._assertion_total_evals[name] = self._assertion_total_evals.get(name, 0) + 1
            for name in failed_names:
                self._assertion_total_failures[name] = self._assertion_total_failures.get(name, 0) + 1

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

    def get_assertion_total_failures(self, assertion_name: str) -> int:
        return self._assertion_total_failures.get(assertion_name, 0)

    def get_assertion_total_evals(self, assertion_name: str) -> int:
        return self._assertion_total_evals.get(assertion_name, 0)

    def get_stuck_items(self, min_streak: int = 3) -> dict[str, int]:
        return {
            item_id: streak
            for item_id, streak in self._item_failure_streaks.items()
            if streak >= min_streak
        }

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
            if item_id in self._item_failed_assertions:
                failed_ids.append(idx)
            else:
                passed_ids.append(idx)

        # Sort by number of failing assertions (worst first), then split
        # into top tier and rest. Sample randomly within each tier.
        failed_ids.sort(
            key=lambda idx: len(self._item_failed_assertions.get(
                self._idx_to_item_id.get(idx, ""), []
            )),
            reverse=True,
        )
        top_k = max(1, int(len(failed_ids) * self.top_failed_fraction))
        top_failed = failed_ids[:top_k]
        rest_failed = failed_ids[top_k:]
        self.rng.shuffle(top_failed)
        self.rng.shuffle(rest_failed)

        selected: list[int] = []
        remaining = n

        n_failed_target = max(self.min_failed_per_batch, remaining // 2)
        n_failed = min(n_failed_target, len(failed_ids), remaining)
        if n_failed > 0:
            # Draw from top tier first, then rest
            from_top = min(n_failed, len(top_failed))
            selected.extend(top_failed[:from_top])
            from_rest = min(n_failed - from_top, len(rest_failed))
            if from_rest > 0:
                selected.extend(rest_failed[:from_rest])
            remaining -= len(selected)

        if remaining > 0 and passed_ids:
            n_passed = min(remaining, len(passed_ids))
            selected.extend(self.rng.sample(passed_ids, n_passed))
            remaining -= n_passed

        if remaining > 0:
            selected_set = set(selected)
            pool = [idx for idx in all_ids if idx not in selected_set]
            n_fill = min(remaining, len(pool))
            if n_fill > 0:
                selected.extend(self.rng.sample(pool, n_fill))

        return selected
