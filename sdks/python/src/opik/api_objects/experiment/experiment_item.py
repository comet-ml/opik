import dataclasses

from typing import Optional


@dataclasses.dataclass
class ExperimentItem:
    dataset_item_id: str
    trace_id: str
    id: Optional[str] = None
