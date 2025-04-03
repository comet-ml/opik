from typing import Dict

import pydantic
from opik_guardrails import schemas

from .. import base_validator
import transformers


class TopicValidationDetails(pydantic.BaseModel):
    matched_topics_scores: Dict[str, float]
    scores: Dict[str, float]


class TopicValidator(base_validator.BaseValidator):
    """A wrapper for the zero-shot classification model."""

    def __init__(self, pipeline: transformers.Pipeline) -> None:
        self._classification_pipeline = pipeline

    def validate(
        self,
        text: str,
        config: schemas.TopicValidationConfig,
    ) -> schemas.ValidationResult:
        classification_result = self._classification_pipeline(text, config.topics)
        scores = {
            label: score
            for label, score in zip(
                classification_result["labels"], classification_result["scores"]
            )
        }

        matched_topics_scores = {
            label: score for label, score in scores.items() if score >= config.threshold
        }

        passed = (
            len(matched_topics_scores) == 0
            if config.mode == "restrict"
            else len(matched_topics_scores) > 0
        )

        return schemas.ValidationResult(
            validation_passed=passed,
            validation_details=TopicValidationDetails(
                matched_topics_scores=matched_topics_scores,
                scores=scores,
            ),
            type=schemas.ValidationType.TOPIC,
            validation_config=config,
        )
