"""Unit tests for the shared score-comparison helper (OPIK-7038)."""

# mypy: disable-error-code=no-untyped-def

import pytest

from opik_optimizer.utils.scoring import improves_over


@pytest.mark.parametrize(
    "score,baseline,expected",
    [
        (0.80, 0.70, True),  # strictly better
        (0.70, 0.70, False),  # exact tie -> keep original
        (0.60, 0.70, False),  # worse
        (0.0, 0.0, False),  # tie at zero
        (1e-9, 0.0, True),  # tiny strict improvement
        (None, 0.70, False),  # missing candidate score
        (0.70, None, False),  # missing baseline
        (None, None, False),  # both missing
    ],
)
def test_improves_over(score, baseline, expected) -> None:
    assert improves_over(score, baseline) is expected
