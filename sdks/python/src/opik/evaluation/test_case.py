from typing import Dict, Any, Optional
import dataclasses


@dataclasses.dataclass
class TestCase:
    trace_id: str
    dataset_item_id: str
    task_output: Dict[str, Any]
    dataset_item_content: Dict[str, Any] = dataclasses.field(default_factory=dict)
    mapped_scoring_inputs: Optional[Dict[str, Any]] = None
