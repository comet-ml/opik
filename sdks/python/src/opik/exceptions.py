from typing import Set, List, TYPE_CHECKING, Dict, Any, Optional, Sequence

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
    def __init__(
        self,
        score_name: str,
        missing_required_arguments: Sequence[str],
        available_keys: Sequence[str],
        unused_mapping_arguments: Optional[Sequence[str]] = None,
    ):
        self.score_name = score_name
        self.missing_required_arguments = missing_required_arguments
        self.available_keys = available_keys
        self.unused_mapping_arguments = unused_mapping_arguments
        super().__init__(self._get_error_message())

    def _get_error_message(self) -> str:
        message = (
            f"The scoring method {self.score_name} is missing arguments: {self.missing_required_arguments}. "
            f"These keys were not present in either the dataset item or the dictionary returned by the evaluation task. "
            f"You can either update the dataset or evaluation task to return this key or use the `scoring_key_mapping` to map existing items to the expected arguments. "
            f"The available keys found in the dataset item and evaluation task output are: {self.available_keys}. "
        )
        if self.unused_mapping_arguments:
            message += f" Some keys in `scoring_key_mapping` didn't match anything: {self.unused_mapping_arguments}"
        return message


class MetricComputationError(OpikException):
    """Exception raised when a metric cannot be computed."""

    pass


class EvaluationError(OpikException):
    """Exception raised when an evaluation fails."""

    pass


class JSONParsingError(OpikException):
    """Exception raised when we fail to parse an LLM response to a dictionary"""

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


class OpikCloudRequestsRateLimited(OpikException):
    """Exception raised when the Opik Cloud limits the request rate."""

    def __init__(self, headers: Dict[str, Any], retry_after: float):
        self.headers = headers
        self.retry_after = retry_after

    def __str__(self) -> str:
        return f"Requests rate limited. Response headers: {self.headers}, retry after: {self.retry_after} seconds"


class ValidationError(OpikException):
    """Exception raised when a validation fails."""

    def __init__(self, prefix: str, failure_reasons: List[str]):
        self._prefix = prefix
        self._failure_reasons = failure_reasons

    def __str__(self) -> str:
        return f"Validation failed in {self._prefix}(): {self._failure_reasons}"

    def __repr__(self) -> str:
        return f"ValidationError(prefix={self._prefix}, failure_reasons={self._failure_reasons})"
