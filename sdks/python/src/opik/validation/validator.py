import abc

from . import result


class Validator(abc.ABC):
    @abc.abstractmethod
    def validate(self) -> result.ValidationResult:
        pass
