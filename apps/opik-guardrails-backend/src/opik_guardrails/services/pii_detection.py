from typing import Dict, List, Optional

import presidio_analyzer
import pydantic


class PIIEntity(pydantic.BaseModel):
    start: int
    end: int
    score: float
    text: str


class PIIDetectionResult(pydantic.BaseModel):
    has_pii: bool
    detected_entities: Dict[str, List[PIIEntity]]


class PIIDetector:
    def __init__(self) -> None:
        self._analyzer_engine = presidio_analyzer.AnalyzerEngine()
        self._default_language = "en"

    def detect(
        self,
        text: str,
        entities_to_detect: Optional[List[str]],
        language: Optional[str],
    ) -> PIIDetectionResult:
        """
        Detect PII in the given text.

        Args:
            text: The text to analyze for PII
            entities_to_detect: Optional list of entity types to detect.
                                If None, all supported entity types will be detected.
            language: Language of the text

        Returns:
            PIIDetectionResult with detection results
        """
        # Analyze the text - if entities_to_detect is None, Presidio will check for all entities
        results = self._analyzer_engine.analyze(
            text=text,
            entities=entities_to_detect,
            language=language or self._default_language,
        )

        grouped_results: Dict[str, List[PIIEntity]] = {}
        for result in results:
            entity_type = result.entity_type
            if entity_type not in grouped_results:
                grouped_results[entity_type] = []

            grouped_results[entity_type].append(
                PIIEntity(
                    start=result.start,
                    end=result.end,
                    score=result.score,
                    text=text[result.start : result.end],
                )
            )

        return PIIDetectionResult(
            has_pii=len(results) > 0, detected_entities=grouped_results
        )
