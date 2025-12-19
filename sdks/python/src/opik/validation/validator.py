import abc
from typing import Any

from . import result


class Validator(abc.ABC):
    @abc.abstractmethod
    def validate(self) -> result.ValidationResult:
        pass


class RaisableValidator(Validator):
    """
    Abstract validator class that extends Validator and adds raise_if_validation_failed method.

    This is used for validators that need to raise ValidationError exceptions
    when validation fails, typically used in class initialization.
    """

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        """
        Initialize the validator.

        Subclasses can override this method with their own initialization signature.
        """
        pass

    @abc.abstractmethod
    def raise_if_validation_failed(self) -> None:
        """
        Raise a ValidationError if validation failed.

        This method should check the validation result and raise an appropriate
        ValidationError exception if validation failed.
        """
        pass
