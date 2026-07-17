import pydantic
import enum
from typing import Dict, Any, List, Optional

from opik.rest_api.types.check_public_result import CheckPublicResult


class ValidationType(str, enum.Enum):
    PII = "PII"
    TOPIC = "TOPIC"
    LLM_JUDGE = "LLM_JUDGE"
    PROMPT_INJECTION = "PROMPT_INJECTION"
    CUSTOM_CLASSIFIER = "CUSTOM_CLASSIFIER"


class ValidationResult(pydantic.BaseModel):
    validation_passed: bool
    type: ValidationType
    validation_config: Dict[str, Any]
    validation_details: Dict[str, Any]


class ValidationResponse(pydantic.BaseModel):
    validation_passed: bool
    validations: List[ValidationResult]
    # This is client side injected
    guardrail_result: Optional[CheckPublicResult] = None
