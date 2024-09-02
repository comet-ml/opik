from typing import Dict, Any, Optional

import dataclasses


@dataclasses.dataclass(frozen=True, eq=True)
class TestRunContent:
    input: Dict[str, Any]
    """The input data for the test."""
    expected_output: Optional[Dict[str, Any]] = None
    """The expected output for the model used in test."""
    metadata: Optional[Dict[str, Any]] = None
    """Additional metadata associated with the test run."""
