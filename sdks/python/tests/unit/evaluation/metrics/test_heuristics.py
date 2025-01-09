import pytest
from opik.evaluation.metrics.heuristics import (
    equals,
    levenshtein_ratio,
    regex_match
)
from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.metrics.heuristics.bleu import BLEU


def test_evaluation__equals():
    metric_param = "some metric"
    metric = equals.Equals(case_sensitive=True)

    assert metric.score(output=metric_param, reference=metric_param) == ScoreResult(
        name=metric.name, value=1.0, reason=None, metadata=None
    )
    assert metric.score(output=metric_param, reference="another value") == ScoreResult(
        name=metric.name, value=0.0, reason=None, metadata=None
    )

def test_evaluation__regex_match():
    # everything that ends with 'metric'
    metric_param = ".+metric$"
    metric = regex_match.RegexMatch(metric_param)

    assert metric.score("some metric") == ScoreResult(
        name=metric.name, value=1.0, reason=None, metadata=None
    )
    assert metric.score("some param") == ScoreResult(
        name=metric.name, value=0.0, reason=None, metadata=None
    )

def test_evaluation__levenshtein_ratio():
    metric_param = "apple"
    metric = levenshtein_ratio.LevenshteinRatio()

    assert metric.score("apple", metric_param) == ScoreResult(
        name=metric.name, value=1.0, reason=None, metadata=None
    )
    assert metric.score("maple", metric_param) == ScoreResult(
        name=metric.name, value=0.8, reason=None, metadata=None
    )
    assert metric.score("qqqqq", metric_param) == ScoreResult(
        name=metric.name, value=0.0, reason=None, metadata=None
    )

@pytest.mark.parametrize(
    "candidate,reference,expected_min,expected_max",
    [
        ("The quick brown fox jumps over the lazy dog",
         "The quick brown fox jumps over the lazy dog", 0.99, 1.01),

        ("The quick brown fox", "The quick green fox jumps over something", 0.05, 0.2),

        ("apple", "orange", -0.01, 0.01),

        ("hello", "hello world", 0.05, 0.5),

        ("", "non-empty reference", -0.01, 0.01),
        ("non-empty candidate", "", -0.01, 0.01),
    ],
)
def test_bleu_score_sentence_level(candidate, reference, expected_min, expected_max):

    metric = BLEU()
    result = metric.score(output=candidate, reference=reference)
    assert isinstance(result, ScoreResult)
    assert expected_min <= result.value <= expected_max, (
        f"For candidate='{candidate}' vs reference='{reference}', "
        f"expected BLEU in [{expected_min}, {expected_max}], got {result.value:.4f}"
    )

@pytest.mark.parametrize(
    "candidate,reference",
    [
        # candidate empty
        ("", "The quick brown fox"),
        # reference empty
        ("The quick brown fox", ""),
    ],
)
def test_bleu_score_empty_cases(candidate, reference):
    """
    Check that empty candidate or reference => BLEU=0,
    plus the reason string indicating emptiness.
    """
    metric = BLEU()
    res = metric.score(candidate, reference)
    assert res.value == 0.0
    if not candidate.strip():
        assert "Candidate is empty" in res.reason
    elif not reference.strip():
        assert "Reference is empty" in res.reason

@pytest.mark.parametrize(
    "candidate,reference,method",
    [
        # "No overlap" => method0 => ~0, method1 => small (>0?), method2 => bigger?
        ("cat", "dog", "method0"),
        ("cat", "dog", "method1"),
        ("cat", "dog", "method2"),

        # Partial overlap => might see differences among smoothing methods
        ("The cat", "cat The", "method0"),
        ("The cat", "cat The", "method1"),
        ("The cat", "cat The", "method2"),
    ],
)

def test_bleu_score_different_smoothing(candidate, reference, method):
    """
    Check that different smoothing yields different non-negative values.
    We won't compare to a single 'expected' number because that depends on
    the exact approach. We just confirm it's valid and >=0.
    """
    metric = BLEU(smoothing_method=method)
    res = metric.score(candidate, reference)
    assert res.value >= 0.0
    assert res.name == "bleu_metric"


@pytest.mark.parametrize(
    "candidates,references,expected_min,expected_max",
    [
        (
            ["The quick brown fox jumps over the lazy dog"],
            [["The quick brown fox jumps over the lazy dog"]],
            0.99,
            1.01,
        ),
        (
            ["The quick brown fox", "Hello world"],
            [
                ["The quick green fox jumps over something"],
                ["Hello there big world"],
            ],
            0.0,
            1.0,
        ),
        (
            [
                "The quick brown fox jumps over the lazy dog",
                "I love apples and oranges"
            ],
            [
                ["The quick brown fox jumps over the lazy dog"],
                ["I love apples and oranges so much!"]
            ],
            0.8,
            1.01,
        ),
        (
            ["", "Some text here"],
            [["non-empty reference"], [""]],
            -0.01,
            0.01,
        ),
    ],
)

def test_bleu_score_corpus(candidates, references, expected_min, expected_max):
    metric = BLEU()
    res = metric.score_corpus(outputs=candidates, references_list=references)
    assert isinstance(res, ScoreResult)

    assert expected_min <= res.value <= expected_max, (
        f"For corpus outputs={candidates} vs references={references}, "
        f"expected BLEU in [{expected_min}, {expected_max}], got {res.value:.4f}"
    )
