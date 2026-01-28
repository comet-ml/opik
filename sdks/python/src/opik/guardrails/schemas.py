import pydantic
import enum
from typing import Any

from opik.rest_api.types.check_public_result import CheckPublicResult


class ValidationType(str, enum.Enum):
    PII = "PII"
    TOPIC = "TOPIC"


class ValidationResult(pydantic.BaseModel):
    validation_passed: bool
    type: ValidationType
    validation_config: dict[str, Any]
    validation_details: dict[str, Any]


class ValidationResponse(pydantic.BaseModel):
    validation_passed: bool
    validations: list[ValidationResult]
    # This is client side injected
    guardrail_result: CheckPublicResult | None = None
