from typing import Optional, Union, Dict, Any

import pydantic

from opik.types import LLMProvider

from . import opik_usage


class LLMUsageInfo(pydantic.BaseModel):
    provider: Optional[Union[LLMProvider, str]] = None
    model: Optional[str] = None
    usage: Optional[Union[Dict[str, Any], opik_usage.OpikUsage]] = None
