import pytest

from opik.exceptions import MetricComputationError
from opik.evaluation.metrics.heuristics import equals, levenshtein_ratio, regex_match
from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.metrics.heuristics.bleu import SentenceBLEU, CorpusBLEU


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
        # Perfect match => BLEU~1.0
        (
            "The quick brown fox jumps over the lazy dog",
            "The quick brown fox jumps over the lazy dog",
            0.99,
            1.01,
        ),
        # Partial overlap => typically ~0.09..0.15 with default 4-gram/method1, so we allow 0.05..0.2
        (
            "The quick brown fox",
            "The quick green fox jumps over something",
            0.05,
            0.2,
        ),
        # Complete mismatch => BLEU ~0.0
        ("apple", "orange", -0.01, 0.01),
        # Single token vs multi-token => small but >0
        ("hello", "hello world", 0.05, 0.5),
    ],
)
def test_sentence_bleu_score(candidate, reference, expected_min, expected_max):
    metric = SentenceBLEU()
    result = metric.score(output=candidate, reference=reference)
    assert isinstance(result, ScoreResult)

    assert expected_min <= result.value <= expected_max, (
        f"For candidate='{candidate}' vs reference='{reference}', "
        f"expected sentence BLEU in [{expected_min}, {expected_max}], got {result.value:.4f}"
    )


@pytest.mark.parametrize(
    "candidate,reference",
    [
        ("", "The quick brown fox"),
        ("The quick brown fox", ""),
    ],
)
def test_sentence_bleu_score_empty_inputs(candidate, reference):
    metric = SentenceBLEU()
    with pytest.raises(MetricComputationError) as exc_info:
        metric.score(candidate, reference)
    assert "empty" in str(exc_info.value).lower()


@pytest.mark.parametrize(
    "candidate,reference,method",
    [
        ("cat", "dog", "method0"),
        ("cat", "dog", "method1"),
        ("cat", "dog", "method2"),
        ("The cat", "cat The", "method0"),
        ("The cat", "cat The", "method1"),
        ("The cat", "cat The", "method2"),
    ],
)
def test_sentence_bleu_score_different_smoothing(candidate, reference, method):
    metric = SentenceBLEU(smoothing_method=method)
    res = metric.score(output=candidate, reference=reference)
    assert res.value >= 0.0
    assert metric.name == "sentence_bleu_metric"


@pytest.mark.parametrize(
    "outputs,references,expected_min,expected_max",
    [
        # Single-pair corpus => near 1.0 if perfect match
        (
            ["The quick brown fox jumps over the lazy dog"],
            [["The quick brown fox jumps over the lazy dog"]],
            0.99,
            1.01,
        ),
        # Multiple partial matches => expect BLEU in [0,1]
        (
            ["The quick brown fox", "Hello world"],
            [
                ["The quick green fox jumps over something"],
                ["Hello there big world"],
            ],
            0.0,
            1.0,
        ),
        # Another multi-sentence scenario with near-perfect matches => near 1.0
        (
            [
                "The quick brown fox jumps over the lazy dog",
                "I love apples and oranges",
            ],
            [
                ["The quick brown fox jumps over the lazy dog"],
                ["I love apples and oranges so much!"],
            ],
            0.8,
            1.01,
        ),
    ],
)
def test_corpus_bleu_score(outputs, references, expected_min, expected_max):
    metric = CorpusBLEU()
    res = metric.score(output=outputs, reference=references)
    assert isinstance(res, ScoreResult)

    assert expected_min <= res.value <= expected_max, (
        f"For corpus outputs={outputs} vs references={references}, "
        f"expected BLEU in [{expected_min}, {expected_max}], got {res.value:.4f}"
    )


@pytest.mark.parametrize(
    "outputs,references",
    [
        # Candidate is empty
        (
            ["", "Some text here"],
            [["non-empty reference"], ["this is fine"]],
        ),
        # Reference is empty
        (
            ["The quick brown fox", "Another sentence"],
            [
                ["The quick brown fox jumps over the lazy dog"],
                [""],
            ],
        ),
    ],
)
def test_corpus_bleu_score_empty_inputs(outputs, references):
    metric = CorpusBLEU()
    with pytest.raises(MetricComputationError) as exc_info:
        metric.score(output=outputs, reference=references)
    assert "empty" in str(exc_info.value).lower()
