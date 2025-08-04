import abc
from typing import Dict, Any

import pydantic
import opik.dict_utils as dict_utils


class BaseOriginalProviderUsage(pydantic.BaseModel, abc.ABC):
    model_config = pydantic.ConfigDict(extra="allow")
    """
    `extra` values are allowed so that the code continued working if provider adds a new field that
    is not reflected in the SDK yet.
    """

    @abc.abstractmethod
    def to_backend_compatible_flat_dict(self, parent_key_prefix: str) -> Dict[str, int]:
        result = {**self.__dict__}

        return self.flatten_result_and_add_model_extra(
            result=result, parent_key_prefix=parent_key_prefix
        )

    @classmethod
    @abc.abstractmethod
    def from_original_usage_dict(
        cls, usage: Dict[str, Any]
    ) -> "BaseOriginalProviderUsage":
        pass

    def flatten_result_and_add_model_extra(
        self, result: Dict[str, Any], parent_key_prefix: str
    ) -> Dict[str, int]:
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
