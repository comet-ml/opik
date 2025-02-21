import dataclasses
from typing import Any, Dict, Optional, Union

import pydantic

from . import result, validator
from ..types import UsageDict, UsageDictGoogle


class PydanticWrapper(pydantic.BaseModel):
    model_config = pydantic.ConfigDict(extra="forbid")
    usage: Union[UsageDict, UsageDictGoogle]


@dataclasses.dataclass
class ParsedUsage:
    full_usage: Optional[Dict[str, Any]] = None
    supported_usage: Optional[Union[UsageDict, UsageDictGoogle]] = None


EXPECTED_TYPES = "{'completion_tokens': int, 'prompt_tokens': int, 'total_tokens': int}"


class UsageValidator(validator.Validator):
    """
    Validator for span token usage
    """

    def __init__(self, usage: Any, provider: Optional[str]):
        self.usage = usage
        self.provider = provider
        self.parsed_usage = ParsedUsage()

    def validate(self) -> result.ValidationResult:
        try:
            if isinstance(self.usage, dict):
                filtered_usage = self.supported_keys

                # run validation
                PydanticWrapper(usage=filtered_usage)

                if self.provider == "google_vertexai":
                    supported_usage = UsageDictGoogle(**filtered_usage)  # type: ignore
                else:
                    supported_usage = UsageDict(**filtered_usage)  # type: ignore
                self.parsed_usage = ParsedUsage(
                    full_usage=self.usage, supported_usage=supported_usage
                )
            else:
                # we already know that usage is invalid but want pydantic to trigger validation error
                PydanticWrapper(usage=self.usage)

            self.validation_result = result.ValidationResult(failed=False)
        except pydantic.ValidationError as exception:
            failure_reasons = []
            for e in exception.errors():
                component_name: str = ".".join(e["loc"])
                component_value: str = e["input"]
                msg: str = (
                    f"{component_name} is invalid or missing.\n"
                    f"Expected keys to have in a dictionary: {EXPECTED_TYPES}.\n"
                    f"Value {repr(component_value)} of type {type(component_value)} was passed."
                )
                failure_reasons.append(msg)
            self.validation_result = result.ValidationResult(
                failed=True, failure_reasons=failure_reasons
            )

        return self.validation_result

    def failure_reason_message(self) -> str:
        assert (
            len(self.validation_result.failure_reasons) > 0
        ), "validate() must be called before accessing failure reason message"
        return self.validation_result.failure_reasons[0]

    @property
    def supported_keys(self) -> Dict[str, Any]:
        if self.provider == "google_vertexai":
            supported_keys = UsageDictGoogle.__annotations__.keys()
        # `openai` and all other
        else:
            supported_keys = UsageDict.__annotations__.keys()

        filtered_usage = {}

        for key in supported_keys:
            if key in self.usage:
                filtered_usage[key] = self.usage[key]

        return filtered_usage
