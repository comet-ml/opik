from typing import Dict, Any
import dataclasses


@dataclasses.dataclass
class TestCase:
    trace_id: str
    dataset_item_id: str
    task_output: Any
    scoring_inputs: Dict[str, Any]
