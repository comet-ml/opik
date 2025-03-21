import collections
from typing import Dict, List

import presidio_analyzer
import pydantic

from opik_guardrails import schemas

from . import base_validator


class PIIEntity(pydantic.BaseModel):
    start: int
    end: int
    score: float
    text: str


class PIIValidationResult(base_validator.ValidationResult):
    detected_entities: Dict[str, List[PIIEntity]]


class PIIValidator(base_validator.BaseValidator):
    def __init__(self) -> None:
        self._analyzer_engine = presidio_analyzer.AnalyzerEngine()
        self._default_language = "en"

    def validate(
        self,
        text: str,
        config: schemas.PIIValidationConfig,
    ) -> PIIValidationResult:
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
            language=config.language or self._default_language,
        )

        grouped_results: Dict[str, List[PIIEntity]] = collections.defaultdict(list)
        for result in results:
            entity_type = result.entity_type
            grouped_results[entity_type].append(
                PIIEntity(
                    start=result.start,
                    end=result.end,
                    score=result.score,
                    text=text[result.start : result.end],
                )
            )

        return PIIValidationResult(
            validation_passed=len(results) == 0,
            detected_entities=grouped_results,
        )
