import pydantic

from typing import Any
from ..types import FeedbackScoreDict
from . import validator, result


class PydanticWrapper(pydantic.BaseModel):
    model_config = pydantic.ConfigDict(extra="forbid")
    feedback_score: FeedbackScoreDict


EXPECTED_TYPES = "{'id': str, 'name': str, 'value': float, 'reason': NotRequired[str], 'category_name': NotRequired[str]}"


class FeedbackScoreValidator(validator.Validator):
    """
    Validator for feedback score dictionary
    """

    def __init__(self, feedback_score: Any):
        self.feedback_score = feedback_score

    def validate(self) -> result.ValidationResult:
        try:
            PydanticWrapper(feedback_score=self.feedback_score)
            self.validation_result = result.ValidationResult(failed=False)
        except pydantic.ValidationError as exception:
            failure_reasons = []
            for e in exception.errors():
                component_name: str = ".".join(e["loc"])
                msg: str = (
                    f"{component_name} - {e['msg']}.\nExpected dict: {EXPECTED_TYPES}."
                )
                failure_reasons.append(msg)
            self.validation_result = result.ValidationResult(
                failed=True, failure_reasons=failure_reasons
            )

        return self.validation_result

    def failure_reason_message(self) -> str:
        assert (
            len(self.validation_result.failure_reasons) > 0
        ), "validate() must be called before accessing failure reason message"
        return self.validation_result.failure_reasons[0]
