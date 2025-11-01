"""Language adherence metric leveraging fastText-style language identification."""

from __future__ import annotations

from typing import Any, Callable, Optional, Tuple

from opik.exceptions import MetricComputationError
from opik.evaluation.metrics.base_metric import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

try:  # optional dependency
    import fasttext
except ImportError:  # pragma: no cover
    fasttext = None  # type: ignore


DetectorFn = Callable[[str], Tuple[str, float]]


class LanguageAdherenceMetric(BaseMetric):
    """
    Check whether text is written in the expected language.

    The metric relies on a fastText language identification model (or a
    user-supplied detector callable) to predict the language of the evaluated text
    and compares it with ``expected_language``. It outputs ``1.0`` when the detected
    language matches and ``0.0`` otherwise, along with the detected label and
    confidence score in ``metadata``.

    References:
      - fastText language identification models
        https://fasttext.cc/docs/en/language-identification.html
      - Joulin et al., "Bag of Tricks for Efficient Text Classification" (EACL 2017)
        https://aclanthology.org/E17-2068/

    Args:
        expected_language: Language code the text should conform to, e.g. ``"en"``.
        model_path: Path to a fastText language identification model. Required unless
            ``detector`` is provided.
        name: Display name for the metric result. Defaults to
            ``"language_adherence_metric"``.
        track: Whether to automatically track metric results. Defaults to ``True``.
        project_name: Optional tracking project name. Defaults to ``None``.
        detector: Optional callable accepting text and returning a
            ``(language, confidence)`` tuple. When provided, ``model_path`` is not
            needed.

    Example:
        >>> from opik.evaluation.metrics import LanguageAdherenceMetric
        >>> # Assuming `lid.176.ftz` is available locally for fastText
        >>> metric = LanguageAdherenceMetric(expected_language="en", model_path="lid.176.ftz")
        >>> result = metric.score("This response is written in English.")  # doctest: +SKIP
        >>> result.value  # doctest: +SKIP
        1.0
    """

    def __init__(
        self,
        expected_language: str,
        model_path: Optional[str] = None,
        name: str = "language_adherence_metric",
        track: bool = True,
        project_name: Optional[str] = None,
        detector: Optional[DetectorFn] = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)
        self._expected_language = expected_language
        self._detector_fn: DetectorFn
        self._model_path = model_path

        self._fasttext_model: Optional[Any]

        if detector is not None:
            self._detector_fn = detector
            self._fasttext_model = None
            return

        if fasttext is None:
            raise ImportError(
                "Install fasttext via `pip install fasttext` and provide a fastText language"
                " model (e.g., lid.176.ftz) or supply a custom detector callable."
            )
        if model_path is None:
            raise ValueError(
                "model_path is required when using the fastText-based detector."
            )
        self._fasttext_model = fasttext.load_model(model_path)
        self._detector_fn = self._predict_with_fasttext

    def score(self, output: str, **ignored_kwargs: Any) -> ScoreResult:
        processed = output
        if not processed.strip():
            raise MetricComputationError("Text is empty for language adherence check.")

        language, confidence = self._detector_fn(processed)
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

    def _predict_with_fasttext(self, text: str) -> tuple[str, float]:
        if self._fasttext_model is None:
            raise MetricComputationError(
                "fastText model is not loaded. Ensure that LanguageAdherenceMetric was initialized with a valid model_path and fastText is installed."
            )
        prediction = self._fasttext_model.predict(text)
        label = prediction[0][0] if prediction[0] else ""
        language = label.replace("__label__", "")
        confidence = float(prediction[1][0]) if prediction[1] else 0.0
        return language, confidence
