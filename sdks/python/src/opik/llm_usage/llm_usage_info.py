from typing import Optional, Union, Dict, Any

import pydantic

from opik.types import LLMProvider

from . import opik_usage


class LLMUsageInfo(pydantic.BaseModel):
    provider: Optional[Union[LLMProvider, str]] = None
    model: Optional[str] = None
    """
    The model name (or model id) that can be used to identify the model for cost calculation.
    """
    usage: Optional[Union[Dict[str, Any], opik_usage.OpikUsage]] = None
    """
    The dictionary with token usage in the original format of the provider.
    """
