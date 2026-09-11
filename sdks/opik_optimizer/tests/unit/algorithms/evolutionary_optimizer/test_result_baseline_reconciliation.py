"""OPIK-7038: evolutionary winner must be reconciled against the baseline.

The HOF/Pareto selection can surface a candidate that did not strictly beat the
seed; `reconcile_winner_with_baseline` falls back to the seed on a tie / no
improvement so the returned prompt matches keep-original-on-tie.
"""

# mypy: disable-error-code=no-untyped-def

from opik_optimizer.algorithms.evolutionary_optimizer.ops.result_ops import (
    reconcile_winner_with_baseline,
)


SEED = {"main": "seed-prompt"}
CANDIDATE = {"main": "evolved-prompt"}


def test_strict_improvement_keeps_candidate() -> None:
    prompts, score = reconcile_winner_with_baseline(
        final_best_prompts=CANDIDATE,
        final_primary_score=0.82,
        seed_prompts=SEED,
        baseline_score=0.70,
    )
    assert prompts is CANDIDATE
    assert score == 0.82


def test_tie_falls_back_to_seed() -> None:
    prompts, score = reconcile_winner_with_baseline(
        final_best_prompts=CANDIDATE,
        final_primary_score=0.70,
        seed_prompts=SEED,
        baseline_score=0.70,
    )
    assert prompts is SEED
    assert score == 0.70


def test_below_baseline_falls_back_to_seed() -> None:
    prompts, score = reconcile_winner_with_baseline(
        final_best_prompts=CANDIDATE,
        final_primary_score=0.55,
        seed_prompts=SEED,
        baseline_score=0.70,
    )
    assert prompts is SEED
    assert score == 0.70
