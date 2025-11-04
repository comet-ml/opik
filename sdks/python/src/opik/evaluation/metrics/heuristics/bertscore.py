from __future__ import annotations

from typing import Any, Callable, Optional, Sequence, Tuple, Union

from opik.exceptions import MetricComputationError
from opik.evaluation.metrics import base_metric, score_result

try:  # pragma: no cover - optional dependency
    from bert_score import score as bert_score_fn
except ImportError:  # pragma: no cover - optional dependency
    bert_score_fn = None


BertScoreFn = Callable[
    [Sequence[str], Union[Sequence[str], Sequence[Sequence[str]]]], Tuple[Any, Any, Any]
]


class BERTScore(base_metric.BaseMetric):
    """Wrapper around the `bert-score` library.

    Args:
        scorer_fn: Optional callable compatible with ``bert_score.score`` for
            dependency injection or advanced usage.
        model_type: Model checkpoint to use when loading the scorer. Ignored when
            ``scorer_fn`` is provided.
        lang: Two-letter language code used by the default scorer.
        rescale_with_baseline: Whether to rescale the score using the provided
            baseline statistics.
        device: Optional device string forwarded to ``bert_score`` (e.g., "cpu",
            "cuda").
    """

    def __init__(
        self,
        scorer_fn: Optional[BertScoreFn] = None,
        model_type: Optional[str] = "bert-base-uncased",
        lang: Optional[str] = "en",
        rescale_with_baseline: bool = False,
        device: Optional[str] = None,
        name: str = "bertscore_metric",
        track: bool = True,
        project_name: Optional[str] = None,
        **scorer_kwargs: Any,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)

        if scorer_fn is not None:
            self._scorer_fn = scorer_fn
        else:
            if bert_score_fn is None:  # pragma: no cover - optional dependency
                raise ImportError(
                    "BERTScore metric requires the optional 'bert-score' package. "
                    "Install via `pip install bert-score` or provide `scorer_fn`."
                )

            def _score(
                candidates: Sequence[str],
                references: Union[Sequence[str], Sequence[Sequence[str]]],
            ) -> Tuple[Any, Any, Any]:
                return bert_score_fn(
                    candidates,
                    references,
                    model_type=model_type,
                    lang=lang,
                    rescale_with_baseline=rescale_with_baseline,
                    device=device,
                    **scorer_kwargs,
                )

            self._scorer_fn = _score

    def score(
        self,
        output: str,
        reference: Union[str, Sequence[str], Sequence[Sequence[str]]],
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        if not output.strip():
            raise MetricComputationError("Candidate is empty (BERTScore metric).")

        references: Union[Sequence[str], Sequence[Sequence[str]]]
        if isinstance(reference, str):
            references = [reference]
        else:
            references = reference
            if isinstance(reference, Sequence) and len(reference) == 0:
                raise MetricComputationError("Reference is empty (BERTScore metric).")

        precision, recall, f1 = self._scorer_fn([output], references)

        score_value = float(f1[0].item() if hasattr(f1[0], "item") else f1[0])
        metadata = {
            "precision": float(
                precision[0].item() if hasattr(precision[0], "item") else precision[0]
            ),
            "recall": float(
                recall[0].item() if hasattr(recall[0], "item") else recall[0]
            ),
        }

        return score_result.ScoreResult(
            value=score_value,
            name=self.name,
            reason=f"BERTScore F1: {score_value:.4f}",
            metadata=metadata,
        )
