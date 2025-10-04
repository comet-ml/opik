from __future__ import annotations

import math
from collections import Counter
from typing import Any, Callable, Dict, Iterable, List, Optional, Protocol, Sequence

from opik.exceptions import MetricComputationError
from opik.evaluation.metrics import base_metric, score_result

TokenizeFn = Callable[[str], Iterable[str]]


class _JSDistanceFn(Protocol):
    def __call__(
        self,
        p: Sequence[float],
        q: Sequence[float],
        base: Optional[
            float
        ] = ...,  # matches scipy signature allowing positional or keyword use
    ) -> float: ...


def _load_jensen_shannon_distance() -> _JSDistanceFn:
    try:
        from scipy.spatial.distance import jensenshannon
    except ImportError as error:  # pragma: no cover - optional dependency
        raise ImportError(
            "Install scipy via `pip install scipy` to use Jensen-Shannon metrics."
        ) from error

    return jensenshannon


def _default_tokenizer(text: str) -> Iterable[str]:
    return text.lower().split()


class _DistributionMetricBase(base_metric.BaseMetric):
    """
    Internal helper for metrics that compare token distributions.

    Args:
        tokenizer: Optional tokenizer returning an iterable of tokens given text.
        name: Display name for the metric.
        track: Whether to automatically track metric results.
        project_name: Optional tracking project.
        normalize: When ``True`` the histogram is converted to probabilities.
        smoothing: Optional additive constant applied during KL-like computations.
    """

    def __init__(
        self,
        tokenizer: Optional[TokenizeFn],
        name: str,
        track: bool,
        project_name: Optional[str],
        normalize: bool,
        smoothing: float = 0.0,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)
        self._tokenizer = tokenizer or _default_tokenizer
        self._normalize = normalize
        self._smoothing = max(0.0, smoothing)

    def _build_distribution(self, text: str) -> Dict[str, float]:
        tokens = list(self._tokenizer(text))
        if len(tokens) == 0:
            raise MetricComputationError(
                "Tokenized text is empty (distribution-based metric)."
            )

        counts = Counter(tokens)
        if not self._normalize:
            return {token: float(count) for token, count in counts.items()}

        total = float(sum(counts.values()))
        return {token: count / total for token, count in counts.items()}

    def _smooth(self, value: float) -> float:
        if self._smoothing == 0.0:
            return value
        return value + self._smoothing


class JSDivergence(_DistributionMetricBase):
    """
    Compute Jensen–Shannon similarity (``1 - JSD``) between two texts.

    Args:
        tokenizer: Optional tokenizer function. Defaults to whitespace split.
        base: Logarithm base used when computing divergence (> ``1.0``).
        normalize: Whether to normalise token counts to probabilities first.
        name: Display name for the metric result.
        track: Whether to automatically track metric results.
        project_name: Optional tracking project name.

    Note:
        Requires :mod:`scipy` to be installed.

    Example:
        >>> from opik.evaluation.metrics import JSDivergence
        >>> metric = JSDivergence()
        >>> result = metric.score(
        ...     output="cat cat sat",
        ...     reference="cat sat on mat",
        ... )
        >>> round(result.value, 3)  # doctest: +SKIP
        0.812
    """

    def __init__(
        self,
        tokenizer: Optional[TokenizeFn] = None,
        base: float = 2.0,
        normalize: bool = True,
        name: str = "js_divergence_metric",
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> None:
        if base <= 1.0:
            raise ValueError("base must be greater than 1.0")
        super().__init__(
            tokenizer=tokenizer,
            name=name,
            track=track,
            project_name=project_name,
            normalize=normalize,
        )
        self._base = base
        self._js_distance_fn = _load_jensen_shannon_distance()

    def score(
        self,
        output: str,
        reference: str,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        if not output.strip():
            raise MetricComputationError(
                "Candidate is empty (Jensen-Shannon divergence)."
            )
        if not reference.strip():
            raise MetricComputationError(
                "Reference is empty (Jensen-Shannon divergence)."
            )

        output_dist = self._build_distribution(output)
        reference_dist = self._build_distribution(reference)

        divergence = self._js_divergence(output_dist, reference_dist)
        score = max(0.0, min(1.0, 1.0 - divergence))

        return score_result.ScoreResult(
            value=score,
            name=self.name,
            reason=(
                f"Jensen-Shannon similarity (base={self._base:g}): {score:.4f} "
                f"(divergence={divergence:.4f})"
            ),
            metadata={
                "divergence": divergence,
                "distance": math.sqrt(divergence),
                "base": self._base,
            },
        )

    def _js_divergence(
        self,
        p_dist: Dict[str, float],
        q_dist: Dict[str, float],
    ) -> float:
        vocabulary = sorted(set(p_dist) | set(q_dist))
        if not vocabulary:
            return 0.0

        p_vector = [p_dist.get(token, 0.0) for token in vocabulary]
        q_vector = [q_dist.get(token, 0.0) for token in vocabulary]

        p_probs = self._ensure_probability_vector(p_vector)
        q_probs = self._ensure_probability_vector(q_vector)

        distance = float(self._js_distance_fn(p_probs, q_probs, base=self._base))
        return distance**2

    def _ensure_probability_vector(self, values: Sequence[float]) -> List[float]:
        total = sum(values)
        if total <= 0.0:
            raise MetricComputationError(
                "Distribution is empty after tokenisation (Jensen-Shannon metric)."
            )
        return [value / total for value in values]


class JSDistance(JSDivergence):
    """
    Return the raw Jensen–Shannon divergence instead of similarity.

    Args:
        tokenizer: Optional tokenizer function.
        base: Logarithm base used for the divergence calculation.
        normalize: Whether to normalise counts into probabilities.
        name: Display name for the metric result.
        track: Whether to automatically track metric results.
        project_name: Optional tracking project name.

    Example:
        >>> from opik.evaluation.metrics import JSDistance
        >>> metric = JSDistance()
        >>> result = metric.score("a a b", reference="a b b")
        >>> round(result.value, 3)  # doctest: +SKIP
        0.188
    """

    def __init__(
        self,
        tokenizer: Optional[TokenizeFn] = None,
        base: float = 2.0,
        normalize: bool = True,
        name: str = "js_distance_metric",
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> None:
        super().__init__(
            tokenizer=tokenizer,
            base=base,
            normalize=normalize,
            name=name,
            track=track,
            project_name=project_name,
        )

    def score(
        self,
        output: str,
        reference: str,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        similarity = super().score(output=output, reference=reference)
        metadata = similarity.metadata or {}
        divergence = float(metadata.get("divergence", 0.0))
        distance = float(metadata.get("distance", math.sqrt(divergence)))
        return score_result.ScoreResult(
            value=divergence,
            name=self.name,
            reason=f"Jensen-Shannon divergence (base={self._base:g}): {divergence:.4f}",
            metadata={
                "distance": distance,
                "base": metadata.get("base", self._base),
            },
        )


class KLDivergence(_DistributionMetricBase):
    """
    Compute the (optionally symmetric) KL divergence between token distributions.

    Args:
        tokenizer: Optional tokenizer function. Defaults to whitespace split.
        direction: Direction to compute (``"pq"``, ``"qp"``, or ``"avg"`` for
            symmetric).
        normalize: Whether to normalise token counts to probabilities first.
        smoothing: Additive smoothing constant to avoid divide-by-zero.
        name: Display name for the metric result.
        track: Whether to automatically track metric results.
        project_name: Optional tracking project name.

    Example:
        >>> from opik.evaluation.metrics import KLDivergence
        >>> metric = KLDivergence(direction="avg")
        >>> result = metric.score("hello hello world", reference="hello world")
        >>> round(result.value, 4)  # doctest: +SKIP
        0.0583
    """

    def __init__(
        self,
        tokenizer: Optional[TokenizeFn] = None,
        direction: str = "pq",
        normalize: bool = True,
        smoothing: float = 1e-12,
        name: str = "kl_divergence_metric",
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> None:
        if direction not in {"pq", "qp", "avg"}:
            raise ValueError("direction must be one of {'pq', 'qp', 'avg'}")
        super().__init__(
            tokenizer=tokenizer,
            name=name,
            track=track,
            project_name=project_name,
            normalize=normalize,
            smoothing=smoothing,
        )
        self._direction = direction

    def score(
        self,
        output: str,
        reference: str,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        if not output.strip():
            raise MetricComputationError("Candidate is empty (KL divergence metric).")
        if not reference.strip():
            raise MetricComputationError("Reference is empty (KL divergence metric).")

        p_dist = self._build_distribution(output)
        q_dist = self._build_distribution(reference)

        if self._direction == "pq":
            divergence = self._kl(p_dist, q_dist)
        elif self._direction == "qp":
            divergence = self._kl(q_dist, p_dist)
        else:
            divergence = 0.5 * (self._kl(p_dist, q_dist) + self._kl(q_dist, p_dist))

        return score_result.ScoreResult(
            value=divergence,
            name=self.name,
            reason=f"KL divergence ({self._direction}): {divergence:.4f}",
        )

    def _kl(self, p_dist: Dict[str, float], q_dist: Dict[str, float]) -> float:
        divergence = 0.0
        for token, p_val in p_dist.items():
            p_val = self._smooth(p_val)
            q_val = self._smooth(q_dist.get(token, 0.0))
            divergence += p_val * math.log(p_val / q_val)
        return divergence
