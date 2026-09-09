import pytest

from opik_backend.score_validation import (
    NO_VALUE,
    SCORING_FAILED,
    describe_unusable,
    unusable_scores,
)


def score(name="metric", value=1.0, scoring_failed=False):
    return {"name": name, "value": value, "scoring_failed": scoring_failed}


@pytest.mark.parametrize("scores, expected", [
    ([score()], []),
    ([score(value=0.0)], []),
    ([score(name="a", value=None)], [("a", NO_VALUE)]),
    ([score(name="a", value=0.0, scoring_failed=True)], [("a", SCORING_FAILED)]),
    ([score(name="a"), score(name="b", value=None)], [("b", NO_VALUE)]),
    ([score(name="a", value=None), score(name="b", value=0.0, scoring_failed=True)],
     [("a", NO_VALUE), ("b", SCORING_FAILED)]),
    ([{"name": "a"}], [("a", NO_VALUE)]),
    (["not a score"], [("", NO_VALUE)]),
    ([], []),
])
def test_unusable_scores_classifies_each_result(scores, expected):
    assert unusable_scores(scores) == expected


def test_zero_is_a_value_not_a_missing_one():
    """A metric answering "no" must reach the backend, so a zero is never unusable on its own."""
    assert unusable_scores([score(name="is_toxic", value=0.0)]) == []


def test_describe_names_each_score_and_its_reason():
    described = describe_unusable([("a", NO_VALUE), ("b", SCORING_FAILED)])

    assert described == "'a' returned no value, 'b' reported the scoring as failed"


def test_describe_renders_an_unnamed_score():
    assert "'<unnamed>'" in describe_unusable([(None, NO_VALUE)])
    assert "'<unnamed>'" in describe_unusable([("", NO_VALUE)])


def test_describe_collapses_line_breaks_in_a_name():
    """The message lands in the rule's user-facing log, so a newline must not forge an entry there."""
    described = describe_unusable([("ok\nERROR [2026-01-01 00:00:00,000] forged entry", NO_VALUE)])

    assert "\n" not in described
    assert "\r" not in described
    assert "ok ERROR" in described


def test_describe_caps_an_oversized_name():
    described = describe_unusable([("x" * 500, NO_VALUE)])

    assert len(described) < 150
    assert "…" in described


def test_describe_caps_the_number_of_names_and_counts_the_remainder():
    described = describe_unusable([(f"score_{i}", NO_VALUE) for i in range(25)])

    assert "'score_0'" in described
    assert "'score_9'" in described
    assert "'score_10'" not in described
    assert "and 15 more" in described
