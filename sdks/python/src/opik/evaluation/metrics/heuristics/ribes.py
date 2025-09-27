"""Wrapper around NLTK's RIBES metric."""

from __future__ import annotations

from typing import Any, Callable, Iterable, List, Optional, Sequence, Union

from opik.evaluation.metrics.base_metric import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
from opik.exceptions import MetricComputationError

try:  # pragma: no cover - optional dependency
    from nltk.translate import ribes_score as nltk_ribes_score
except ImportError:  # pragma: no cover - optional dependency
    nltk_ribes_score = None

TokenizeFn = Callable[[str], Iterable[str]]
RibesFn = Callable[[Sequence[str], Sequence[Sequence[str]]], float]


class RIBES(BaseMetric):
    """Compute the RIBES score between an output and one or more references."""

    def __init__(
        self,
        name: str = "ribes_metric",
        track: bool = True,
        project_name: Optional[str] = None,
        tokenizer: Optional[TokenizeFn] = None,
        ribes_fn: Optional[RibesFn] = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)
        self._tokenizer = tokenizer or (lambda text: text.split())
        if ribes_fn is not None:
            self._ribes_fn = ribes_fn
        else:
            if nltk_ribes_score is None:  # pragma: no cover - optional dependency
                raise ImportError(
                    "RIBES metric requires the optional 'nltk' package. Install via"
                    " `pip install nltk` or provide `ribes_fn`."
                )
            self._ribes_fn = lambda hyp, refs: nltk_ribes_score.sentence_ribes(hyp, refs)

    def score(
        self,
        output: str,
        reference: Union[str, Sequence[str]],
        **ignored_kwargs: Any,
    ) -> ScoreResult:
        if not output.strip():
            raise MetricComputationError("Candidate is empty (RIBES metric).")

        hypothesis_tokens = list(self._tokenizer(output))
        if isinstance(reference, str):
            refs = [reference]
        else:
            refs = list(reference)
        if not refs or any(not ref.strip() for ref in refs):
            raise MetricComputationError("Reference is empty (RIBES metric).")

        references_tokens = [list(self._tokenizer(ref)) for ref in refs]
        value = float(self._ribes_fn(hypothesis_tokens, references_tokens))

        return ScoreResult(
            value=value,
            name=self.name,
            reason=f"RIBES score: {value:.4f}",
        )
