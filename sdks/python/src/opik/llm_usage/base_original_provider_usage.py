import abc
from typing import Dict, Any

import pydantic
from opik import dict_utils


class BaseOriginalProviderUsage(pydantic.BaseModel, abc.ABC):
    model_config = pydantic.ConfigDict(extra="allow")
    """
    `extra` values are allowed so that the code continued working if provider adds a new field that
    is not reflected in the SDK yet.
    """

    @abc.abstractmethod
    def to_backend_compatible_flat_dict(self, parent_key_prefix: str) -> Dict[str, int]:
        result = {**self.__dict__}

        result = dict_utils.flatten_dict(
            d=result, delim=".", parent_key=parent_key_prefix
        )

        if self.model_extra is not None:
            model_extra = dict_utils.flatten_dict(
                d=self.model_extra, delim=".", parent_key=parent_key_prefix
            )
            result.update(model_extra)

        result = dict_utils.keep_only_values_of_type(d=result, value_type=int)

        return result

    @classmethod
    @abc.abstractmethod
    def from_original_usage_dict(
        cls, usage: Dict[str, Any]
    ) -> "BaseOriginalProviderUsage":
        pass
