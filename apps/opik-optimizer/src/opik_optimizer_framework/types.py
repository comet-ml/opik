from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


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


@dataclass(frozen=True)
class SplitResult:
    train_item_ids: list[str]
    validation_item_ids: list[str]
    dataset_size: int
    seed: int


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
