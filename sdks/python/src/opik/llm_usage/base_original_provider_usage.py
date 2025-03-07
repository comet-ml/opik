import abc
from typing import Dict, Any

import pydantic


class BaseOriginalProviderUsage(pydantic.BaseModel, abc.ABC):
    model_config = pydantic.ConfigDict(extra="allow")
    """
    `extra` values are allowed so that the code continued working if provider adds a new field that
    is not reflected in the SDK yet.
    """

    @abc.abstractmethod
    def to_backend_compatible_flat_dict(self, parent_key_prefix: str) -> Dict[str, int]:
        pass

    @classmethod
    @abc.abstractmethod
    def from_original_usage_dict(
        cls, usage: Dict[str, Any]
    ) -> "BaseOriginalProviderUsage":
        pass
