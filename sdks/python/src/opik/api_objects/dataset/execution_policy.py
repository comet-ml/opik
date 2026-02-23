"""Execution policy type and default for dataset evaluation."""

from typing import TypedDict


class ExecutionPolicy(TypedDict, total=False):
    """
    Execution policy for evaluation suite items.

    Attributes:
        runs_per_item: Number of times to run evaluation per item.
        pass_threshold: Minimum number of passing runs required for item to pass.
    """

    runs_per_item: int
    pass_threshold: int


DEFAULT_EXECUTION_POLICY: ExecutionPolicy = {
    "runs_per_item": 1,
    "pass_threshold": 1,
}
