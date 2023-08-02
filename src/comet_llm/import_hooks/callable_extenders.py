import dataclasses
from typing import List

from .types import AfterCallback, AfterExceptionCallback, BeforeCallback


@dataclasses.dataclass
class CallableExtenders:
    before: List[BeforeCallback]
    after: List[AfterCallback]
    after_exception: List[AfterExceptionCallback]


def get() -> CallableExtenders:
    return CallableExtenders([], [], [])
