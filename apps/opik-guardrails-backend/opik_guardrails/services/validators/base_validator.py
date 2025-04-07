import abc

from opik_guardrails import schemas


class BaseValidator(abc.ABC):
    @abc.abstractmethod
    def validate(
        self, text: str, validation_config: schemas.ValidationConfig
    ) -> schemas.ValidationResult:
        pass
