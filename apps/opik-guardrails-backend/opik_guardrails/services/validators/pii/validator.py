import collections
from typing import Dict, List

import pydantic
import presidio_analyzer

from opik_guardrails import schemas

from .. import base_validator


class PIIEntity(pydantic.BaseModel):
    start: int
    end: int
    score: float
    text: str


class PIIValidationDetails(pydantic.BaseModel):
    detected_entities: Dict[str, List[PIIEntity]]


class PIIValidator(base_validator.BaseValidator):
    def __init__(self, engine: presidio_analyzer.AnalyzerEngine) -> None:
        self._analyzer_engine = engine

    def validate(
        self,
        text: str,
        config: schemas.PIIValidationConfig,
    ) -> schemas.ValidationResult:
        """
        Detect PII in the given text.

        Args:
            text: The text to analyze for PII
            config: Configuration for the PII detection

        Returns:
            PIIValidationResult with detection results
        """
        results = self._analyzer_engine.analyze(
            text=text,
            entities=config.entities,
            language=config.language,
        )

        grouped_results: Dict[str, List[PIIEntity]] = collections.defaultdict(list)

        validation_passed = True

        for result in results:
            entity_type = result.entity_type
            if result.score < config.threshold:
                continue

            validation_passed = False
            grouped_results[entity_type].append(
                PIIEntity(
                    start=result.start,
                    end=result.end,
                    score=result.score,
                    text=text[result.start : result.end],
                )
            )

        return schemas.ValidationResult(
            validation_passed=validation_passed,
            validation_details=PIIValidationDetails(detected_entities=grouped_results),
            type=schemas.ValidationType.PII,
            validation_config=config,
        )
