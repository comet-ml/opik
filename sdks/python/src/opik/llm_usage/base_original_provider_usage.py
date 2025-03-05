import abc
from typing import Dict, Any

import pydantic


class BaseOriginalProviderUsage(pydantic.BaseModel, abc.ABC):
    model_config = pydantic.ConfigDict(extra="allow")

    @abc.abstractmethod
    def to_backend_compatible_flat_dict(self) -> Dict[str, int]:
        pass

    @classmethod
    @abc.abstractmethod
    def from_original_usage_dict(usage: Dict[str, Any]) -> "BaseOriginalProviderUsage":
        pass