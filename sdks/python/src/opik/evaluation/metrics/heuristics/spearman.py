"""Spearman rank correlation between reference and predicted rankings."""

from __future__ import annotations

from typing import Any, Sequence

from opik.evaluation.metrics.base_metric import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
from opik.exceptions import MetricComputationError


class SpearmanRanking(BaseMetric):
    """
    Compute Spearman's rank correlation for two rankings of the same items.

    Scores are normalised to ``[0.0, 1.0]`` where `1.0` indicates perfect rank
    agreement and `0.0` indicates complete disagreement (``rho = -1``).

    References:
      - Spearman's rank correlation coefficient (Wikipedia overview)
        https://en.wikipedia.org/wiki/Spearman%27s_rank_correlation_coefficient
      - SciPy documentation: ``scipy.stats.spearmanr``
        https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.spearmanr.html

    Args:
        name: Display name for the metric result. Defaults to
            ``"spearman_ranking_metric"``.
        track: Whether to automatically track metric results. Defaults to ``True``.
        project_name: Optional tracking project name. Defaults to ``None``.

    Example:
        >>> from opik.evaluation.metrics import SpearmanRanking
        >>> metric = SpearmanRanking()
        >>> result = metric.score(
        ...     output=["b", "a", "c"],
        ...     reference=["a", "b", "c"],
        ... )
        >>> round(result.metadata["rho"], 2)  # doctest: +SKIP
        -0.5
    """

    def __init__(
        self,
        name: str = "spearman_ranking_metric",
        track: bool = True,
        project_name: str | None = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)

    def score(
        self,
        output: Sequence[Any],
        reference: Sequence[Any],
        **ignored_kwargs: Any,
    ) -> ScoreResult:
        if len(output) != len(reference):
            raise MetricComputationError(
                "output and reference rankings must have the same length."
            )
        if len(output) == 0:
            raise MetricComputationError(
                "Rankings cannot be empty for Spearman correlation."
            )

        ref_ranks = {item: idx for idx, item in enumerate(reference)}
        if set(output) != set(reference):
            raise MetricComputationError("Rankings must contain the same items.")

        diffs_sq = 0
        for idx, item in enumerate(output):
            ref_idx = ref_ranks[item]
            diffs_sq += (idx - ref_idx) ** 2

        n = len(output)
        if n == 1:
            rho = 1.0
        else:
            rho = 1 - (6 * diffs_sq) / (n * (n * n - 1))

        # normalize to [0, 1] for convenience
        normalized = (rho + 1) / 2

        return ScoreResult(
            value=normalized,
            name=self.name,
            reason=f"Spearman correlation (normalized): {normalized:.4f}",
            metadata={"rho": rho},
        )
