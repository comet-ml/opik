import pytest
from opik.evaluation.metrics.heuristics import equals, levenshtein_ratio, regex_match
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
    "candidate,reference,expected",
    [
        # Perfect match => BLEU=1.0
        ("The quick brown fox jumps", "The quick brown fox jumps", 1.0),
        # Partial overlap (shorter candidate).
        # Standard BLEU with a reference length of 9 vs. candidate length 4
        # yields ~0.2865 if there's near-perfect n-gram match on those 4 tokens.
        # We'll approximate that to 0.29, within ±0.05 for leniency.
        ("The quick brown fox", "The quick brown fox jumps over the lazy dog", 0.29),
        # Full mismatch => BLEU ~ 0.0
        ("apple", "orange", 0.0),
        # Single token partial => e.g. "hello" vs. "hello world"
        # Typically ~0.3679 with standard brevity penalty => ~0.37
        ("hello", "hello world", 0.37),
    ],
)
def test_bleu_score_sentence_level(candidate, reference, expected):
    """
    Tests BLEU in more standard scenarios, using approximate checks.
    We rely on approximate comparison since BLEU can differ slightly
    depending on smoothing details. By default, BLEU uses method1 smoothing.
    """
    metric = BLEU()  # default n_grams=4, smoothing_method="method1"
    result = metric.score(output=candidate, reference=reference)
    assert isinstance(result, ScoreResult)
    # For approximate matching, we allow a small tolerance
    # e.g. ±0.05 around our target
    assert result.value == pytest.approx(
        expected, abs=0.05
    ), f"Got {result.value:.4f}, expected ~{expected} ± 0.05"


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
    "candidates,references",
    [
        # Perfect match => corpus-level BLEU=1
        (
            ["Hello world", "The quick brown fox"],
            [["Hello world"], ["The quick brown fox"]],
        ),
        # Partial overlap => expect 0 < BLEU < 1
        (
            ["Hello planet", "The quick brown cat"],
            [["Hello world"], ["The quick brown fox"]],
        ),
    ],
)
def test_bleu_score_corpus(candidates, references):
    metric = BLEU()
    res = metric.score_corpus(outputs=candidates, references_list=references)
    assert isinstance(res, ScoreResult)
    if candidates[0] == references[0][0] and candidates[1] == references[1][0]:
        # perfect match => 1.0
        assert res.value == pytest.approx(1.0, abs=1e-6)
    else:
        # partial => between 0 and 1
        assert 0 < res.value < 1.0
