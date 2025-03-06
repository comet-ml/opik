import abc
from typing import Dict, Any, Literal

import pydantic


class BaseOriginalProviderUsage(pydantic.BaseModel, abc.ABC):
    model_config = pydantic.ConfigDict(extra="allow")
    _PARENT_KEY_PREFIX: Literal["original"] = "original"

    @abc.abstractmethod
    def to_backend_compatible_flat_dict(self) -> Dict[str, int]:
        pass

    @classmethod
    @abc.abstractmethod
    def from_original_usage_dict(
        cls, usage: Dict[str, Any]
    ) -> "BaseOriginalProviderUsage":
        pass
