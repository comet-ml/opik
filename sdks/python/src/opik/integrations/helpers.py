import dataclasses
from typing import Optional

from opik.types import UsageDict


@dataclasses.dataclass
class LLMUsageInfo:
    provider: Optional[str] = None
    model: Optional[str] = None
    usage: Optional[UsageDict] = None
