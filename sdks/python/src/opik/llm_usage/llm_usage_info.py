from typing import Any

import pydantic

from opik.types import LLMProvider

from . import opik_usage


class LLMUsageInfo(pydantic.BaseModel):
    provider: LLMProvider | str | None = None
    model: str | None = None
    """
    The model name (or model id) that can be used to identify the model for cost calculation.
    """
    usage: dict[str, Any] | opik_usage.OpikUsage | None = None
    """
    The dictionary with token usage in the original format of the provider.
    """
