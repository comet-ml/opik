import dataclasses
import enum


class EntityType(str, enum.Enum):
    # v1 scope is intentionally narrow: only TRACE and SPAN. The backend's
    # DATASET / DATASET_ITEM / PROJECT / THREAD types aren't stored in the
    # local emulator, so we don't expose them on the tool surface yet.
    TRACE = "trace"
    SPAN = "span"


@dataclasses.dataclass(frozen=True)
class EntityRef:
    type: EntityType
    id: str
