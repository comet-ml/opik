import pydantic

from typing import Any
from ..types import UsageDict
from . import validator, result


class PydanticWrapper(pydantic.BaseModel):
    model_config = pydantic.ConfigDict(extra="forbid")
    usage: UsageDict


EXPECTED_TYPES = "{'completion_tokens': int, 'prompt_tokens': int, 'total_tokens': int}"


class UsageValidator(validator.Validator):
    """
    Validator for span token usage
    """

    def __init__(self, usage: Any):
        self.usage = usage

    def validate(self) -> result.ValidationResult:
        try:
            PydanticWrapper(usage=self.usage)
            self.validation_result = result.ValidationResult(failed=False)
        except pydantic.ValidationError as exception:
            failure_reasons = []
            for e in exception.errors():
                component_name: str = ".".join(e["loc"])
                component_value: str = e["input"]
                msg: str = (
                    f"{component_name} has incorrect type.\n"
                    f"Value {repr(component_value)} of type {type(component_value)} was passed.\n"
                    f"Expected types: {EXPECTED_TYPES}."
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
