from typing import Optional, Union

import pydantic

from opik.types import LLMProvider

from . import opik_usage


class LLMUsageInfo(pydantic.BaseModel):
    provider: Optional[Union[LLMProvider, str]] = None
    model: Optional[str] = None
    usage: Optional[opik_usage.OpikUsage] = None
