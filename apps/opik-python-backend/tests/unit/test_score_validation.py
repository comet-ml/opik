import pytest

from opik_backend.score_validation import has_usable_score


def score(name="metric", value=1.0, scoring_failed=False):
    return {"name": name, "value": value, "scoring_failed": scoring_failed}


@pytest.mark.parametrize("scores, expected", [
    ([score()], True),
    ([score(value=0.0)], True),
    ([score(name="a", value=None)], False),
    ([score(name="a", value=0.0, scoring_failed=True)], False),
    ([score(name="a"), score(name="b", value=None)], True),
    ([score(name="a", value=None), score(name="b", value=0.0, scoring_failed=True)], False),
    ([{"name": "a"}], False),
    (["not a score"], False),
    ([], False),
])
def test_has_usable_score(scores, expected):
    assert has_usable_score(scores) is expected


def test_zero_is_a_value_not_a_missing_one():
    """A metric answering "no" must reach the backend, so a zero is never unusable on its own."""
    assert has_usable_score([score(name="is_toxic", value=0.0)]) is True


@pytest.mark.parametrize("flag", ["false", "true", 1, 0, "", None])
def test_only_a_real_true_counts_as_a_failed_scoring(flag):
    """Rejection here is all-or-nothing, so a truthy non-boolean must not reject what the backend stores."""
    assert has_usable_score([score(name="a", value=1.0, scoring_failed=flag)]) is True


def test_a_real_true_counts_as_a_failed_scoring():
    assert has_usable_score([score(name="a", value=1.0, scoring_failed=True)]) is False
