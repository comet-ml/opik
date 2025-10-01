"""Language adherence metric leveraging fastText-style language identification."""

from __future__ import annotations

from typing import Any, Optional

from opik.evaluation.metrics.base_metric import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
from opik.exceptions import MetricComputationError

try:  # optional dependency
    import fasttext
except ImportError:  # pragma: no cover
    fasttext = None  # type: ignore


class LanguageAdherenceMetric(BaseMetric):
    """Checks whether text adheres to an expected language code."""

    def __init__(
        self,
        expected_language: str,
        model_path: Optional[str] = None,
        name: str = "language_adherence_metric",
        track: bool = True,
        project_name: Optional[str] = None,
        detector: Optional[Any] = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)
        self._expected_language = expected_language
        self._model_path = model_path

        if detector is not None:
            self._detector = detector
        else:
            if fasttext is None:
                raise ImportError(
                    "Install fasttext via `pip install fasttext` and provide a fastText language"
                    " model (e.g., lid.176.ftz) or supply a custom detector callable."
                )
            if model_path is None:
                raise ValueError(
                    "model_path is required when using the fastText-based detector."
                )
            self._detector = fasttext.load_model(model_path)

    def score(self, output: str, **ignored_kwargs: Any) -> ScoreResult:
        processed = self._preprocess(output)
        if not processed.strip():
            raise MetricComputationError("Text is empty for language adherence check.")

        language, confidence = self._detect_language(processed)
        adherence = 1.0 if language == self._expected_language else 0.0

        metadata = {
            "detected_language": language,
            "confidence": confidence,
            "expected_language": self._expected_language,
        }

        reason = (
            "Language adheres to expectation"
            if adherence == 1.0
            else f"Detected language '{language}' differs from expected '{self._expected_language}'"
        )

        return ScoreResult(
            value=adherence, name=self.name, reason=reason, metadata=metadata
        )

    def _detect_language(self, text: str) -> tuple[str, float]:
        if callable(self._detector):
            return self._detector(text)

        prediction = self._detector.predict(text)
        label = prediction[0][0] if prediction[0] else ""
        language = label.replace("__label__", "")
        confidence = float(prediction[1][0]) if prediction[1] else 0.0
        return language, confidence
