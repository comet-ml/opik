import dataclasses
import enum


class EntityType(str, enum.Enum):
    TRACE = "trace"
    SPAN = "span"
    DATASET = "dataset"
    DATASET_ITEM = "dataset_item"
    PROJECT = "project"
    THREAD = "thread"


@dataclasses.dataclass(frozen=True)
class EntityRef:
    type: EntityType
    id: str
