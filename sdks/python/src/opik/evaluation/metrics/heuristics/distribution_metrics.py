from __future__ import annotations

import math
from collections import Counter
from typing import Any, Callable, Dict, Iterable, Optional

from opik.exceptions import MetricComputationError
from opik.evaluation.metrics import base_metric, score_result

TokenizeFn = Callable[[str], Iterable[str]]


def _default_tokenizer(text: str) -> Iterable[str]:
    return text.lower().split()


class _DistributionMetricBase(base_metric.BaseMetric):
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
    """Jensen–Shannon divergence similarity metric returning ``1 - JSD``."""

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
        self._log_base = math.log(base)

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
                "base": self._base,
            },
        )

    def _js_divergence(
        self,
        p_dist: Dict[str, float],
        q_dist: Dict[str, float],
    ) -> float:
        vocabulary = set(p_dist.keys()) | set(q_dist.keys())
        mixture = {}
        for token in vocabulary:
            p_val = p_dist.get(token, 0.0)
            q_val = q_dist.get(token, 0.0)
            mixture[token] = 0.5 * (p_val + q_val)

        return 0.5 * (
            self._kl_divergence(p_dist, mixture)
            + self._kl_divergence(q_dist, mixture)
        )

    def _kl_divergence(
        self, dist: Dict[str, float], mixture: Dict[str, float]
    ) -> float:
        divergence = 0.0
        for token, value in dist.items():
            if value == 0.0:
                continue
            mix_val = mixture.get(token, 0.0)
            if mix_val == 0.0:
                continue
            divergence += value * (math.log(value / mix_val) / self._log_base)
        return divergence


class JSDistance(JSDivergence):
    """Returns the raw Jensen–Shannon divergence (distance)."""

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
        divergence = similarity.metadata["divergence"] if similarity.metadata else 0.0
        return score_result.ScoreResult(
            value=divergence,
            name=self.name,
            reason=f"Jensen-Shannon divergence (base={self._base:g}): {divergence:.4f}",
        )


class KLDivergence(_DistributionMetricBase):
    """Computes the KL divergence between token distributions."""

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
