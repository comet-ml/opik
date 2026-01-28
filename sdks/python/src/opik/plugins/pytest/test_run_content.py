from typing import Any

import dataclasses


@dataclasses.dataclass(frozen=True, eq=True)
class TestRunContent:
    input: dict[str, Any]
    """The input data for the test."""
    expected_output: dict[str, Any] | None = None
    """The expected output for the model used in test."""
    metadata: dict[str, Any] | None = None
    """Additional metadata associated with the test run."""
