from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Literal


CandidateConfig = dict[str, Any]


@dataclass(frozen=True)
class Candidate:
    candidate_id: str
    config: CandidateConfig
    step_index: int
    parent_candidate_ids: list[str] = field(default_factory=list)


@dataclass
class TrialResult:
    candidate_id: str
    step_index: int
    score: float
    metric_scores: dict[str, float]
    experiment_id: str | None
    experiment_name: str | None
    config: CandidateConfig
    parent_candidate_ids: list[str] = field(default_factory=list)
    internal_optimization_score: float | None = field(default=None, repr=False)

    @property
    def optimization_score(self) -> float:
        """Internal blended score used by the algorithm for candidate ranking.

        Falls back to ``score`` (pass_rate) when blended scoring is not used.
        """
        return self.internal_optimization_score if self.internal_optimization_score is not None else self.score


@dataclass(frozen=True)
class SplitResult:
    train_item_ids: list[str]
    validation_item_ids: list[str]
    dataset_size: int
    seed: int


@dataclass
class ScoringConfig:
    strategy: Literal["blended", "pass_rate"] = "blended"
    pass_rate_weight: float = 1.0
    assertion_rate_weight: float | None = None  # None = auto: 1/(num_items+1)


@dataclass
class OptimizationContext:
    optimization_id: str
    dataset_name: str
    model: str
    metric_type: str
    optimizer_type: str
    optimizer_parameters: dict[str, Any]
    optimizable_keys: list[str]
    baseline_config: CandidateConfig = field(default_factory=dict)
    config_descriptions: dict[str, str] = field(default_factory=dict)
    split_strategy: str = "80_20"
    evaluator_model: str | None = None
    scoring_config: ScoringConfig = field(default_factory=ScoringConfig)



@dataclass
class OptimizationState:
    status: str = "running"
    step_index: int = 0
    total_steps: int = 2
    trials: list[TrialResult] = field(default_factory=list)
    best_trial: TrialResult | None = None
    error: str | None = None


@dataclass
class OptimizationResult:
    best_trial: TrialResult | None
    all_trials: list[TrialResult]
    score: float
    initial_score: float | None = None
