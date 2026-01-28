from typing import Any
import dataclasses


@dataclasses.dataclass
class TestCase:
    trace_id: str
    dataset_item_id: str
    task_output: dict[str, Any]
    dataset_item_content: dict[str, Any] = dataclasses.field(default_factory=dict)
    mapped_scoring_inputs: dict[str, Any] | None = None
