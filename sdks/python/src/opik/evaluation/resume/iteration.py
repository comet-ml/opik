"""
Pure decision functions used during resume iteration.

These work over a :class:`ResumeContext` and a :class:`DatasetItem` and decide:
- how many runs an item is *expected* to have
- how many more runs it *still needs*
- which items to yield to the engine (with per-item ``runs_per_item``
  overridden to the remaining count)

The engine already supports per-item execution policy overrides, so no engine
changes are needed for resume — we just hand it items pre-annotated.
"""

from typing import Iterator, TYPE_CHECKING

from ...api_objects.dataset import dataset_item

if TYPE_CHECKING:
    from . import context as _context


def expected_runs_for_item(
    context: "_context.ResumeContext",
    item: dataset_item.DatasetItem,
) -> int:
    """
    Resolve how many runs are expected for this item in total.

    Per-item ``execution_policy.runs_per_item`` (e.g. test-suite items) wins
    over the experiment-level default; falls back to the default otherwise.
    """
    if (
        item.execution_policy is not None
        and item.execution_policy.runs_per_item is not None
    ):
        return item.execution_policy.runs_per_item
    return context.default_runs_per_item


def remaining_runs_for_item(
    context: "_context.ResumeContext",
    item: dataset_item.DatasetItem,
) -> int:
    """
    How many more runs this item still needs.

    Trials of the same item are independent, so resume only replays the
    runs that did not complete. Returns ``expected - completed`` clamped
    at zero.
    """
    expected = expected_runs_for_item(context, item)
    completed = context.completed_runs_by_item_id.get(item.id, 0)
    return max(0, expected - completed)


def build_pending_items_iterator(
    items_iter: Iterator[dataset_item.DatasetItem],
    context: "_context.ResumeContext",
) -> Iterator[dataset_item.DatasetItem]:
    """
    Yield only items that still need work, with
    ``execution_policy.runs_per_item`` set to the count of runs still
    missing for that item (see :func:`remaining_runs_for_item`).
    """
    for item in items_iter:
        remaining = remaining_runs_for_item(context, item)
        if remaining == 0:
            continue

        existing_pass_threshold = (
            item.execution_policy.pass_threshold
            if item.execution_policy is not None
            else None
        )
        item.execution_policy = dataset_item.ExecutionPolicyItem(
            runs_per_item=remaining,
            pass_threshold=existing_pass_threshold,
        )
        yield item


def is_fully_completed(
    context: "_context.ResumeContext",
    item: dataset_item.DatasetItem,
) -> bool:
    """Whether this dataset item has every expected trial already done."""
    return remaining_runs_for_item(context, item) == 0
