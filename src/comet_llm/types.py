import dataclasses
from typing import Any

JSONEncodable = Any


@dataclasses.dataclass
class Timestamp:
    start: int
    end: int
    duration: int = dataclasses.field(init=False)

    def __post_init__(self):
        self.duration = self.end - self.start
