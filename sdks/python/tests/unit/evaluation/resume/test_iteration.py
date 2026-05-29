from unittest import mock

from opik.api_objects.dataset import dataset_item
from opik.evaluation.resume import context, iteration


def _make_context(
    completed: dict = None, default: int = 1
) -> context.ResumeContext:
    return context.ResumeContext(
        experiment=mock.Mock(),
        dataset=mock.Mock(),
        completed_runs_by_item_id=completed or {},
        default_runs_per_item=default,
        dataset_filter_string=None,
        nb_samples=None,
        candidate_dataset_item_ids=None,
    )


class TestExpectedRunsForItem:
    def test_item_without_execution_policy__uses_context_default(self):
        ctx = _make_context(default=5)
        item = dataset_item.DatasetItem(id="item-1")

        assert iteration.expected_runs_for_item(ctx, item) == 5

    def test_item_with_runs_per_item__overrides_default(self):
        ctx = _make_context(default=2)
        item = dataset_item.DatasetItem(
            id="item-1",
            execution_policy=dataset_item.ExecutionPolicyItem(runs_per_item=7),
        )

        assert iteration.expected_runs_for_item(ctx, item) == 7

    def test_item_with_only_pass_threshold__falls_back_to_default(self):
        ctx = _make_context(default=4)
        item = dataset_item.DatasetItem(
            id="item-1",
            execution_policy=dataset_item.ExecutionPolicyItem(pass_threshold=1),
        )

        assert iteration.expected_runs_for_item(ctx, item) == 4


class TestRemainingRunsForItem:
    def test_no_completed_runs__returns_full_count(self):
        ctx = _make_context(completed={}, default=3)
        item = dataset_item.DatasetItem(id="item-1")

        assert iteration.remaining_runs_for_item(ctx, item) == 3

    def test_partial_completion__redoes_all_trials(self):
        """Partial items get all trials redone from scratch — see the
        all-or-nothing rationale in ``remaining_runs_for_item``."""
        ctx = _make_context(completed={"item-1": 1}, default=3)
        item = dataset_item.DatasetItem(id="item-1")

        assert iteration.remaining_runs_for_item(ctx, item) == 3

    def test_fully_completed__returns_zero(self):
        ctx = _make_context(completed={"item-1": 3}, default=3)
        item = dataset_item.DatasetItem(id="item-1")

        assert iteration.remaining_runs_for_item(ctx, item) == 0

    def test_over_completed__returns_zero(self):
        ctx = _make_context(completed={"item-1": 5}, default=3)
        item = dataset_item.DatasetItem(id="item-1")

        assert iteration.remaining_runs_for_item(ctx, item) == 0

    def test_per_item_override__beats_default__partial_redoes_all(self):
        ctx = _make_context(completed={"item-1": 2}, default=10)
        item = dataset_item.DatasetItem(
            id="item-1",
            execution_policy=dataset_item.ExecutionPolicyItem(runs_per_item=5),
        )

        # Partial (2 of 5 done) → redo all 5, not the missing 3.
        assert iteration.remaining_runs_for_item(ctx, item) == 5

    def test_per_item_override__fully_completed_returns_zero(self):
        ctx = _make_context(completed={"item-1": 5}, default=10)
        item = dataset_item.DatasetItem(
            id="item-1",
            execution_policy=dataset_item.ExecutionPolicyItem(runs_per_item=5),
        )

        assert iteration.remaining_runs_for_item(ctx, item) == 0


class TestIsFullyCompleted:
    def test_returns_true_when_completed_meets_expected(self):
        ctx = _make_context(completed={"item-1": 3}, default=3)
        item = dataset_item.DatasetItem(id="item-1")

        assert iteration.is_fully_completed(ctx, item) is True

    def test_returns_false_when_partial(self):
        ctx = _make_context(completed={"item-1": 1}, default=3)
        item = dataset_item.DatasetItem(id="item-1")

        assert iteration.is_fully_completed(ctx, item) is False

    def test_returns_false_when_pending(self):
        ctx = _make_context(completed={}, default=3)
        item = dataset_item.DatasetItem(id="item-1")

        assert iteration.is_fully_completed(ctx, item) is False


class TestBuildPendingItemsIterator:
    def test_skips_fully_completed_items_only(self):
        ctx = _make_context(
            completed={"done-1": 3, "partial-1": 1, "done-2": 3}, default=3
        )
        items = [
            dataset_item.DatasetItem(id="done-1"),
            dataset_item.DatasetItem(id="partial-1"),
            dataset_item.DatasetItem(id="done-2"),
            dataset_item.DatasetItem(id="fresh-1"),
        ]

        pending = list(iteration.build_pending_items_iterator(iter(items), ctx))

        assert [item.id for item in pending] == ["partial-1", "fresh-1"]

    def test_sets_full_trial_count_on_yielded_items(self):
        """Partial AND pending items both get the full trial count: partial
        items are redone from scratch, pending items run all trials fresh."""
        ctx = _make_context(completed={"partial-1": 1}, default=3)
        items = [
            dataset_item.DatasetItem(id="partial-1"),
            dataset_item.DatasetItem(id="fresh-1"),
        ]

        pending = list(iteration.build_pending_items_iterator(iter(items), ctx))

        # partial-1 had 1 of 3 done → all 3 redone
        assert pending[0].execution_policy.runs_per_item == 3
        # fresh-1 had 0 of 3 done → all 3 run
        assert pending[1].execution_policy.runs_per_item == 3

    def test_preserves_existing_pass_threshold(self):
        ctx = _make_context(completed={}, default=2)
        items = [
            dataset_item.DatasetItem(
                id="item-1",
                execution_policy=dataset_item.ExecutionPolicyItem(
                    runs_per_item=4,
                    pass_threshold=3,
                ),
            ),
        ]

        pending = list(iteration.build_pending_items_iterator(iter(items), ctx))

        assert pending[0].execution_policy.runs_per_item == 4
        assert pending[0].execution_policy.pass_threshold == 3
