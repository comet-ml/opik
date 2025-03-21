import abc

import pydantic

from opik_guardrails import schemas


class ValidationResult(pydantic.BaseModel):
    validation_passed: bool
    type: schemas.ValidationType
    validation_config: schemas.ValidationConfig
    validation_details: pydantic.BaseModel


class BaseValidator(abc.ABC):
    @abc.abstractmethod
    def validate(
        self, text: str, validation_config: schemas.ValidationConfig
    ) -> ValidationResult:
        pass
