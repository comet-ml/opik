import abc

from .result import ValidationResult


class Validator(abc.ABC):
    @abc.abstractmethod
    def validate(self) -> ValidationResult:
        pass
