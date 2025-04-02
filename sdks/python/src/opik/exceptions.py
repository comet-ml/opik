from typing import Set, List, TYPE_CHECKING

if TYPE_CHECKING:
    from opik.guardrails import schemas


class OpikException(Exception):
    pass


class DatasetItemUpdateOperationRequiresItemId(OpikException):
    pass


class ContextExtractorNotSet(OpikException):
    pass


class ConfigurationError(OpikException):
    pass


class ScoreMethodMissingArguments(OpikException):
    pass


class MetricComputationError(Exception):
    """Exception raised when a metric cannot be computed."""

    pass


class PromptPlaceholdersDontMatchFormatArguments(OpikException):
    def __init__(self, prompt_placeholders: Set[str], format_arguments: Set[str]):
        self.prompt_placeholders = prompt_placeholders
        self.format_arguments = format_arguments
        self.symmetric_difference = prompt_placeholders.symmetric_difference(
            format_arguments
        )

    def __str__(self) -> str:
        return (
            f"The `prompt.format(**kwargs)` arguments must exactly match the prompt placeholders. "
            f"Prompt placeholders: {list(self.prompt_placeholders)}. "
            f"Format arguments: {list(self.format_arguments)}. "
            f"Difference: {list(self.symmetric_difference)}. "
        )


class ExperimentNotFound(OpikException):
    pass


class DatasetNotFound(OpikException):
    pass


class GuardrailValidationFailed(OpikException):
    """Exception raised when a guardrail validation fails."""

    def __init__(
        self,
        message: str,
        validation_results: List["schemas.ValidationResult"],
        failed_validations: List["schemas.ValidationResult"],
    ):
        self.message = message
        self.validation_results = validation_results
        self.failed_validations = failed_validations
        super().__init__(message)

    def __str__(self) -> str:
        return f"{self.message}. Failed validations: {self.failed_validations}\n"
