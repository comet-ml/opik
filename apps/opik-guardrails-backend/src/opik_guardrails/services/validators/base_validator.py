import abc

import pydantic

from opik_guardrails import schemas


class ValidationResult(pydantic.BaseModel):
    validation_passed: bool


class BaseValidator(abc.ABC):
    @abc.abstractmethod
    def validate(
        self, text: str, validation_config: schemas.ValidationConfig
    ) -> ValidationResult:
        pass
