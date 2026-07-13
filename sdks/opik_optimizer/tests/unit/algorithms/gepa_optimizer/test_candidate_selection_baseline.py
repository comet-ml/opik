"""OPIK-7038: tie policy in GEPA's select_best_candidate_index.

A candidate must STRICTLY beat the baseline to win; a tie returns the -1
sentinel so the caller falls back to the seed prompt.
"""

# mypy: disable-error-code=no-untyped-def

import pytest

from opik_optimizer.algorithms.gepa_optimizer.ops import candidate_ops


def _indexed(n: int):
    return [(i, {}) for i in range(n)]


@pytest.mark.parametrize(
    "rescored,initial_score,expected",
    [
        ([0.70, 0.82, 0.88], 0.70, (2, 0.88)),  # clear winner
        ([0.70, 0.70, 0.70], 0.70, (-1, 0.70)),  # all tie baseline -> seed
        ([0.65, 0.60], 0.70, (-1, 0.70)),  # none beat baseline -> seed
        ([0.70, 0.70001, 0.70], 0.70, (1, 0.70001)),  # barely beats
        ([0.88, 0.88], 0.88, (-1, 0.88)),  # tie at the top score -> seed
    ],
)
def test_select_best_candidate_index_tie_policy(
    rescored, initial_score, expected
) -> None:
    result = candidate_ops.select_best_candidate_index(
        rescored=rescored,
        filtered_val_scores=list(rescored),
        filtered_indexed_candidates=_indexed(len(rescored)),
        initial_score=initial_score,
        gepa_result=None,
    )
    assert result == expected
