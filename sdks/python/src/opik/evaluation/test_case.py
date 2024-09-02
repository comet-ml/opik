import dataclasses
from . import task_output


@dataclasses.dataclass
class TestCase:
    trace_id: str
    dataset_item_id: str
    task_output: task_output.TaskOutput
