import re

import pytest

from opik.exceptions import MetricComputationError
from opik.evaluation.metrics.heuristics import (
    equals,
    levenshtein_ratio,
    regex_match,
    rouge,
)
from opik.evaluation.metrics.heuristics.contains import Contains
from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.metrics.heuristics.bleu import SentenceBLEU, CorpusBLEU
from opik.evaluation.metrics.heuristics.distribution_metrics import (
    JSDivergence,
    JSDistance,
    KLDivergence,
)
from opik.evaluation.metrics.heuristics.meteor import METEOR
from opik.evaluation.metrics.heuristics.gleu import GLEU
from opik.evaluation.metrics.heuristics.bertscore import BERTScore
from opik.evaluation.metrics.heuristics.chrf import ChrF
from opik.evaluation.metrics.heuristics.spearman import SpearmanRanking
from opik.evaluation.metrics.heuristics.vader_sentiment import VADERSentiment
from opik.evaluation.metrics.heuristics.readability import Readability
from opik.evaluation.metrics.heuristics.tone import Tone

# NLTK emits a noisy warning for BLEU test cases with zero higher-order overlaps.
pytestmark = pytest.mark.filterwarnings(
    "ignore:\\nThe hypothesis contains 0 counts of 2-gram overlaps\\.:UserWarning"
)


class CustomTokenizer:
    def __init__(self, delimiter=" "):
        self.delimiter = delimiter

    def tokenize(self, text):
        return text.split(self.delimiter)


# --- NEW: Test cases for the Contains metric have been added below ---


def test_contains_with_default_reference():
    """Happy Flow: Tests that the metric correctly uses the default reference."""
    metric = Contains(reference="world", case_sensitive=False, track=False)
    assert metric.score(output="Hello, beautiful World!").value == 1.0
    assert metric.score(output="Hello, beautiful planet!").value == 0.0


def test_contains_with_case_sensitive_default_reference():
    """Happy Flow: Tests the case_sensitive flag."""
    metric = Contains(reference="World", case_sensitive=True, track=False)
    assert metric.score(output="Hello, world!").value == 0.0
    assert metric.score(output="Hello, World!").value == 1.0


def test_contains_with_overridden_reference():
    """Happy Flow: Tests that a reference in score() overrides the default one."""
    metric = Contains(reference="world", track=False)
    result = metric.score(output="Hello, there!", reference="there")
    assert result.value == 1.0


def test_contains_with_no_default_reference():
    """Happy Flow: Tests providing the reference only in the score() call."""
    metric = Contains(track=False)
    result = metric.score(output="An example sentence.", reference="example")
    assert result.value == 1.0


def test_contains_raises_error_if_no_reference_is_provided():
    """Edge Case: Tests ValueError when no default is set and score() gets None."""
    metric = Contains(track=False)
    with pytest.raises(ValueError) as excinfo:
        metric.score(output="Some text", reference=None)

    # This should match the error for a missing reference
    expected_error_msg = "No reference string provided."
    assert expected_error_msg in str(excinfo.value)


@pytest.mark.parametrize("invalid_ref", ["", None])
def test_contains_raises_error_for_invalid_default_reference(invalid_ref):
    """Edge Case: Tests ValueError when the default reference is None or empty."""
    metric = Contains(reference=invalid_ref, track=False)
    with pytest.raises(ValueError) as excinfo:
        metric.score(output="Some text")

    # Check for the correct error message based on the input
    if invalid_ref is None:
        expected_error_msg = "No reference string provided."
    else:  # empty string
        expected_error_msg = "Invalid reference string provided."

    assert expected_error_msg in str(excinfo.value)


@pytest.mark.parametrize("invalid_ref", ["", None])
def test_contains_raises_error_for_invalid_overridden_reference(invalid_ref):
    """Edge Case: Tests ValueError when the override reference is None or empty."""

    metric = Contains(reference="A valid default", track=False)

    if invalid_ref is None:
        # The override is None, so ref becomes the default "A valid default". No error is raised.
        # This test should instead confirm the fallback works.
        assert metric.score(output="A valid default", reference=None).value == 1.0
    else:
        # The override is "", which is an invalid value. An error should be raised.
        with pytest.raises(ValueError) as excinfo:
            metric.score(output="Some text", reference=invalid_ref)
        expected_error_msg = "Invalid reference string provided."
        assert expected_error_msg in str(excinfo.value)


# --- Existing test cases below this line ---


def test_evaluation__equals():
    metric_param = "some metric"
    metric = equals.Equals(case_sensitive=True, track=False)

    assert metric.score(output=metric_param, reference=metric_param) == ScoreResult(
        name=metric.name, value=1.0, reason=None, metadata=None
    )
    assert metric.score(output=metric_param, reference="another value") == ScoreResult(
        name=metric.name, value=0.0, reason=None, metadata=None
    )


def test_evaluation__equals_with_numeric_inputs():
    """Test that Equals metric handles numeric inputs by converting to strings."""
    metric = equals.Equals(track=False)

    # Integer to integer comparison
    assert metric.score(output=42, reference=42) == ScoreResult(
        name=metric.name, value=1.0, reason=None, metadata=None
    )
    assert metric.score(output=42, reference=43) == ScoreResult(
        name=metric.name, value=0.0, reason=None, metadata=None
    )

    # Float to float comparison
    assert metric.score(output=3.14, reference=3.14) == ScoreResult(
        name=metric.name, value=1.0, reason=None, metadata=None
    )

    # Integer to string comparison (should match when string representations are equal)
    assert metric.score(output=42, reference="42") == ScoreResult(
        name=metric.name, value=1.0, reason=None, metadata=None
    )
    assert metric.score(output="42", reference=42) == ScoreResult(
        name=metric.name, value=1.0, reason=None, metadata=None
    )

    # Mixed types that don't match
    assert metric.score(output=42, reference="forty-two") == ScoreResult(
        name=metric.name, value=0.0, reason=None, metadata=None
    )


def test_evaluation__regex_match():
    # everything that ends with 'metric'
    metric_param = ".+metric$"
    metric = regex_match.RegexMatch(metric_param, track=False)

    assert metric.score("some metric") == ScoreResult(
        name=metric.name, value=1.0, reason=None, metadata=None
    )
    assert metric.score("some param") == ScoreResult(
        name=metric.name, value=0.0, reason=None, metadata=None
    )


def test_evaluation__levenshtein_ratio():
    metric_param = "apple"
    metric = levenshtein_ratio.LevenshteinRatio(track=False)

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
    metric = SentenceBLEU(track=False)
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
    metric = SentenceBLEU(track=False)
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
    metric = SentenceBLEU(smoothing_method=method, track=False)
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
    metric = CorpusBLEU(track=False)
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
    metric = CorpusBLEU(track=False)
    with pytest.raises(MetricComputationError) as exc_info:
        metric.score(output=outputs, reference=references)
    assert "empty" in str(exc_info.value).lower()


def test_js_divergence_identical_text():
    metric = JSDivergence(track=False)
    result = metric.score(
        output="The quick brown fox jumps over the lazy dog",
        reference="The quick brown fox jumps over the lazy dog",
    )

    assert isinstance(result, ScoreResult)
    assert result.value == pytest.approx(1.0, abs=1e-6)
    assert result.metadata is not None
    assert result.metadata["divergence"] == pytest.approx(0.0, abs=1e-6)


def test_js_divergence_different_text():
    metric = JSDivergence(track=False)
    result = metric.score(output="apple pear", reference="zebra quokka")

    assert isinstance(result, ScoreResult)
    # Divergence in log base 2 should be close to 1 for disjoint vocab
    assert 0.0 <= result.value < 0.1
    assert 0.9 < result.metadata["divergence"] <= 1.0


def test_js_divergence_requires_non_empty():
    metric = JSDivergence(track=False)

    with pytest.raises(MetricComputationError):
        metric.score(output="", reference="non empty")

    with pytest.raises(MetricComputationError):
        metric.score(output="non empty", reference="   ")


def test_js_distance_matches_metadata():
    metric = JSDistance(track=False)
    result = metric.score(output="token token", reference="token other")
    assert 0.0 <= result.value <= 1.0


def test_kl_divergence_avg_direction():
    metric = KLDivergence(direction="avg", smoothing=1e-6, track=False)
    result = metric.score(output="cat cat", reference="cat dog")
    assert result.value >= 0.0


def test_meteor_metric_with_custom_fn():
    captured = []

    def meteor_fn(references, hypothesis):
        captured.append((tuple(references), hypothesis))
        return 0.88

    metric = METEOR(meteor_fn=meteor_fn, track=False)
    res = metric.score(output="hello world", reference="hello world")

    assert res.value == pytest.approx(0.88)
    assert captured == [(("hello world",), "hello world")]


def test_meteor_rejects_empty_inputs():
    metric = METEOR(meteor_fn=lambda refs, hyp: 1.0, track=False)
    with pytest.raises(MetricComputationError):
        metric.score(output="", reference="ref")
    with pytest.raises(MetricComputationError):
        metric.score(output="hyp", reference="   ")


def test_gleu_metric_with_custom_fn():
    def gleu_fn(references, hypothesis):
        return 0.5

    metric = GLEU(gleu_fn=gleu_fn, track=False)
    res = metric.score(output="a b", reference="a b")
    assert res.value == pytest.approx(0.5)


def test_gleu_rejects_empty_inputs():
    metric = GLEU(gleu_fn=lambda refs, hyp: 0.0, track=False)

    with pytest.raises(MetricComputationError):
        metric.score(output="", reference="text")

    with pytest.raises(MetricComputationError):
        metric.score(output="summary", reference=[""])


class _Scalar:
    def __init__(self, value: float) -> None:
        self._value = value

    def item(self) -> float:
        return self._value


def test_bertscore_with_stubbed_fn():
    def scorer(cands, refs):
        assert cands == ["hello"]
        assert refs == ["hello"]
        return ([_Scalar(0.8)], [_Scalar(0.75)], [_Scalar(0.77)])

    metric = BERTScore(scorer_fn=scorer, track=False)
    result = metric.score(output="hello", reference="hello")

    assert result.value == pytest.approx(0.77)
    assert result.metadata is not None
    assert result.metadata["precision"] == pytest.approx(0.8)
    assert result.metadata["recall"] == pytest.approx(0.75)


def test_bertscore_rejects_empty_candidate():
    metric = BERTScore(scorer_fn=lambda c, r: ([0.0], [0.0], [0.0]), track=False)
    with pytest.raises(MetricComputationError):
        metric.score(output="   ", reference="ref")


def test_chrf_metric_uses_custom_fn():
    def chrf_fn(candidate, references):
        assert candidate == "hello world"
        assert references == ["hello world"]
        return 0.72

    metric = ChrF(chrf_fn=chrf_fn, track=False)
    result = metric.score(output="hello world", reference="hello world")

    assert result.value == pytest.approx(0.72)


def test_spearman_ranking_metric():
    metric = SpearmanRanking(track=False)
    result = metric.score(output=["b", "a", "c"], reference=["a", "b", "c"])

    assert result.metadata["rho"] == pytest.approx(0.5)
    assert result.value == pytest.approx((0.5 + 1) / 2)


def test_vader_sentiment_metric_uses_custom_analyzer():
    class StubAnalyzer:
        def polarity_scores(self, text: str) -> dict:
            assert text == "hello"
            return {"compound": -0.4, "pos": 0.2}

    metric = VADERSentiment(analyzer=StubAnalyzer(), track=False)
    result = metric.score(output="hello")

    assert result.value == pytest.approx((-0.4 + 1) / 2)
    assert result.metadata["vader"]["compound"] == -0.4


def test_readability_metric_and_guard_behaviour():
    class StubTextStat:
        def sentence_count(self, text: str) -> int:
            count = sum(text.count(mark) for mark in ".!?")
            return count or 1

        def lexicon_count(self, text: str, removepunct: bool = True) -> int:
            if removepunct:
                text = text.translate({ord(ch): " " for ch in ",;:()[]"})
            return len([word for word in text.split() if word])

        def syllable_count(self, text: str, lang: str = "en_US") -> int:
            def syllables(word: str) -> int:
                cleaned = re.sub(r"[^a-z]", "", word.lower())
                if not cleaned:
                    return 1
                vowels = "aeiouy"
                count = 0
                prev_is_vowel = False
                for char in cleaned:
                    is_vowel = char in vowels
                    if is_vowel and not prev_is_vowel:
                        count += 1
                    prev_is_vowel = is_vowel
                if cleaned.endswith("e") and count > 1:
                    count -= 1
                return max(1, count)

            return sum(syllables(word) for word in text.split())

        def _reading_stats(self, text: str) -> tuple[float, float]:
            sentences = self.sentence_count(text)
            words = self.lexicon_count(text)
            syllables = self.syllable_count(text)
            words_per_sentence = words / sentences if sentences else 0
            syllables_per_word = syllables / words if words else 0
            reading_ease = (
                206.835 - 1.015 * words_per_sentence - 84.6 * syllables_per_word
            )
            fk_grade = 0.39 * words_per_sentence + 11.8 * syllables_per_word - 15.59
            return reading_ease, fk_grade

        def flesch_reading_ease(self, text: str) -> float:
            return self._reading_stats(text)[0]

        def flesch_kincaid_grade(self, text: str) -> float:
            return self._reading_stats(text)[1]

    readability = Readability(track=False, textstat_module=StubTextStat())
    easy_text = (
        "We processed your insurance claim and scheduled an adjuster visit for tomorrow "
        "morning."
    )
    hard_text = (
        "Pursuant to the aforementioned clause, fiduciary responsibilities"
        " shall be irrevocably devolved."
    )

    easy_result = readability.score(output=easy_text)
    hard_result = readability.score(output=hard_text)

    assert 0.0 <= easy_result.value <= 1.0
    assert 0.0 <= hard_result.value <= 1.0
    assert easy_result.value > hard_result.value
    assert easy_result.metadata is not None
    assert hard_result.metadata is not None
    assert (
        hard_result.metadata["flesch_kincaid_grade"]
        > easy_result.metadata["flesch_kincaid_grade"]
    )
    assert easy_result.metadata["within_grade_bounds"] is True
    assert hard_result.metadata["within_grade_bounds"] is True

    threshold = easy_result.metadata["flesch_kincaid_grade"] + 1.0
    guard = Readability(
        max_grade=threshold,
        enforce_bounds=True,
        track=False,
        textstat_module=StubTextStat(),
    )
    strict_guard = Readability(
        min_grade=threshold,
        enforce_bounds=True,
        track=False,
        textstat_module=StubTextStat(),
    )

    assert guard.score(output=easy_text).value == 1.0
    assert strict_guard.score(output=easy_text).value == 0.0


def test_tone_metric_detects_shouting_and_negativity():
    metric = Tone(track=False, max_exclamations=1, max_upper_ratio=0.2)

    polite = "Thanks for your patience. I'm happy to help you resolve this."
    rude = "THIS IS TERRIBLE!!! YOU ARE USELESS!!!"

    assert metric.score(output=polite).value == 1.0
    assert metric.score(output=rude).value == 0.0


# ROUGE score tests


def test_rouge_score_invalid_rouge_type():
    with pytest.raises(MetricComputationError) as exc_info:
        rouge.ROUGE(rouge_type="rouge55")
    assert "invalid rouge_type" in str(exc_info.value).lower()


def test_rouge_score_for_invalid_reference_type():
    metric = rouge.ROUGE(track=False)
    with pytest.raises(MetricComputationError) as exc_info:
        metric.score("candidate", [1, False, -3, 4])
    assert (
        str(exc_info.value).lower()
        == "reference must be a string or a list of strings."
    )


@pytest.mark.parametrize(
    "candidate,reference",
    [
        ("", "The quick brown fox"),
        ("The quick brown fox", ""),
        ("The quick brown fox", ["the quick brown fox", ""]),
    ],
)
def test_rouge_score_for_empty_inputs(candidate, reference):
    metric = rouge.ROUGE(track=False)
    with pytest.raises(MetricComputationError) as exc_info:
        metric.score(candidate, reference)
    assert "empty" in str(exc_info.value).lower()


def test_rouge_lsum_available():
    metric = rouge.ROUGE(rouge_type="rougeLsum", track=False)
    result = metric.score(output="foo\nbar", reference="foo\nqux")
    assert 0.0 <= result.value <= 1.0


@pytest.mark.parametrize(
    "candidate,reference,expected_min,expected_max",
    [
        # Perfect match => ~1.0
        (
            "The quick brown fox jumps over the lazy dog",
            "The quick brown fox jumps over the lazy dog",
            0.99,
            1.01,
        ),
        # Partial overlap => hence greater than 0.5 less than 0.75
        # Matches => "The" "brown" "fox"
        # Precision = 3/3 = 1.0
        # Recall = 3/6 = 0.5
        # F1 = 2 * (1.0 * 0.5) / (1.0 + 0.5) = 0.6667
        (
            "The brown fox",
            "The quick brown fox moves quickly",
            0.65,
            0.67,
        ),
        # No overlap => ~0.0
        (
            "A green dog",
            "The quick brown fox moves quickly",
            0.0,
            0.01,
        ),
    ],
)
def test_rouge1_score(candidate, reference, expected_min, expected_max):
    metric = rouge.ROUGE(rouge_type="rouge1", track=False)
    result = metric.score(output=candidate, reference=reference)
    assert isinstance(result, ScoreResult)

    assert expected_min <= result.value <= expected_max, (
        f"For candidate='{candidate}' vs reference='{reference}', "
        f"expected rouge1 score in [{expected_min}, {expected_max}], got {result.value:.4f}"
    )


@pytest.mark.parametrize(
    "candidate,reference,expected_min,expected_max",
    [
        # Perfect match => ~1.0
        (
            "The quick brown fox jumps over the lazy dog",
            "The quick brown fox jumps over the lazy dog",
            0.99,
            1.01,
        ),
        # No overlap => ~0.0
        (
            "A green dog",
            "The quick brown fox moves quickly",
            0.0,
            0.01,
        ),
        # Rouge 2 uses bigrams
        # Candidate = "the brown", "brown fox"
        # Reference = "the quick, quick brown", "brown fox, fox moves, moves quickly"
        # Match => "brown fox"
        # Precision = 1/2 = 0.5
        # Recall = 1/5 = 0.2
        # F1 = 2 * (0.5 * 0.2) / (0.5 + 0.2) = 0.2857
        (
            "The brown fox",
            "The quick brown fox moves quickly",
            0.27,
            0.29,
        ),
    ],
)
def test_rouge2_score(candidate, reference, expected_min, expected_max):
    metric = rouge.ROUGE(rouge_type="rouge2", track=False)
    result = metric.score(output=candidate, reference=reference)
    assert isinstance(result, ScoreResult)

    assert expected_min <= result.value <= expected_max, (
        f"For candidate='{candidate}' vs reference='{reference}', "
        f"expected rouge2 score in [{expected_min}, {expected_max}], got {result.value:.4f}"
    )


@pytest.mark.parametrize(
    "candidate,reference,expected_min,expected_max",
    [
        # Perfect match => ~1.0
        (
            "The quick brown fox jumps over the lazy dog",
            "The quick brown fox jumps over the lazy dog",
            0.99,
            1.01,
        ),
        # No overlap => ~0.0
        (
            "A green dog",
            "The quick brown fox moves quickly",
            0.0,
            0.01,
        ),
        # Rouge L uses longest common subsequence i.e. the longest sequence of words (not necessarily consecutive, but still in order)
        # Candidate = "the brown fox"
        # Reference = "the quick brown fox moves quickly"
        # LCS => "the brown fox"
        # ROUGE-L precision is the ratio of the length of the LCS, over the number of unigrams in candidate.
        # Precision = 3/3 = 1.0
        # ROUGE-L recall is the ratio of the length of the LCS, over the number of unigrams in reference.
        # Recall = 3/6 = 0.5
        # F1 = 2 * (1.0 * 0.5) / (1.0 + 0.5) = 0.6667
        (
            "The brown fox",
            "The quick brown fox moves quickly",
            0.65,
            0.67,
        ),
    ],
)
def test_rougeL_score(candidate, reference, expected_min, expected_max):
    metric = rouge.ROUGE(rouge_type="rougeL", track=False)
    result = metric.score(output=candidate, reference=reference)
    assert isinstance(result, ScoreResult)
    assert expected_min <= result.value <= expected_max, (
        f"For candidate='{candidate}' vs reference='{reference}', "
        f"expected rougeL score in [{expected_min}, {expected_max}], got {result.value:.4f}"
    )


@pytest.mark.parametrize(
    "candidate,reference,expected_min,expected_max",
    [
        # ROUGE-Lsum splits the text into sentences based on newlines and
        # computes the LCS for each pair of sentences and
        # take the average score for all sentences.
        # Candidate = "John is an accomplished artist.\\n He is part of a music band"
        # Reference = "John is a talented musician.\\n He has a band called as 'The Band'"
        # Split based on newlines:
        # Candidate = ["John is an accomplished artist.", " He is part of a music band"]
        # Reference = ["John is a talented musician.", " He has a band called as 'The Band'"]
        # LCS for first pair = "John is"
        # Precision = 2/5 = 0.4
        # Recall = 2/5 = 0.4
        # F1 = 2 * (0.4 * 0.4) / (0.4 + 0.4) = 0.4
        # LCS for second pair = "He a band"
        # Precision = 3/7 = 0.4286
        # Recall = 3/8 = 0.375
        # F1 = 2 * (0.4286 * 0.375) / (0.4286 + 0.375) = 0.4
        # Average of both = (0.4 + 0.4) / 2 = 0.4
        (
            "John is an accomplished artist.\n He is part of a music band",
            "John is a talented musician.\n He has a band called as 'The Band'",
            0.40,
            0.45,
        ),
    ],
)
def test_rougeLsum_score(candidate, reference, expected_min, expected_max):
    metric = rouge.ROUGE(rouge_type="rougeLsum", track=False)
    result = metric.score(output=candidate, reference=reference)
    assert isinstance(result, ScoreResult)
    assert expected_min <= result.value <= expected_max, (
        f"For candidate='{candidate}' vs reference='{reference}', "
        f"expected rougeLsum score in [{expected_min}, {expected_max}], got {result.value:.4f}"
    )


@pytest.mark.parametrize(
    "candidate,reference,expected_min,expected_max",
    [
        # Calculates rouge scores between targets and prediction.
        # The target with the maximum f-measure is used for the final score
        # Candidate = "The brown fox jumps quickly"
        # Reference = ["The fox moves", "The quick brown fox jumps over the lazy dog"]
        # Matches for reference 1 => "The" "fox"
        # # Precision = 2/5 = 0.4
        # # Recall = 2/3 = 0.6667
        # # F1 = 2 * (0.4 * 0.6667) / (0.4 + 0.6667) = 0.5
        # Matches for reference 2 => "The" "brown" "fox" "jumps"
        # # Precision = 4/4 = 1.0
        # # Recall = 4/8 = 0.5
        # # F1 = 2 * (1.0 * 0.5) / (1.0 + 0.5) = 0.6667
        # Hence, the final score = 0.6667
        (
            "The brown fox jumps quickly",
            ["The fox moves quickly", "The quick brown fox jumps over the lazy dog"],
            0.65,
            0.67,
        ),
    ],
)
def test_rouge_score_for_multiple_references(
    candidate, reference, expected_min, expected_max
):
    metric = rouge.ROUGE(track=False)
    result = metric.score(output=candidate, reference=reference)
    assert isinstance(result, ScoreResult)

    assert expected_min <= result.value <= expected_max, (
        f"For candidate='{candidate}' vs reference='{reference}', "
        f"expected rouge1 score for multiple references in [{expected_min}, {expected_max}], got {result.value:.4f}"
    )


@pytest.mark.parametrize(
    "candidate,reference,expected_min,expected_max",
    [
        # Porter stemmer - removes plurals and word suffixes such as (ing, ion, ment)
        # Candidate = "The brown dogs jumps on the log quickly"
        # Reference = "The quick brown fox jumps over the lazy dog"
        # Stemmed Candidate = "the brown dog jump on the log quick"
        # Stemmed Reference = "the quick brown fox jump over the lazy dog"
        # Matches => "the" "brown" "dog" "jump" "quick"
        # Precision = 5/8 = 0.625
        # Recall = 5/9 = 0.5556
        # F1 = 2 * (0.625 * 0.5556) / (0.625 + 0.5556) = 0.5882
        # Hence, the final score = 0.5882
        (
            "The brown dogs jumps on the log quickly",
            "The quick brown fox jumps over the lazy dog",
            0.57,
            0.59,
        ),
    ],
)
def test_rouge_score_using_stemmer(candidate, reference, expected_min, expected_max):
    metric = rouge.ROUGE(use_stemmer=True, track=False)
    result = metric.score(output=candidate, reference=reference)
    assert isinstance(result, ScoreResult)

    assert expected_min <= result.value <= expected_max, (
        f"For candidate='{candidate}' vs reference='{reference}', "
        f"expected rouge1 score in [{expected_min}, {expected_max}], got {result.value:.4f}"
    )


@pytest.mark.parametrize(
    "candidate,reference,expected_min,expected_max,tokenizer",
    [
        # Custom tokenizer - splits based on commas
        # Candidate = "Bread and butter, Bun and cream"
        # Reference = "Bread and butter, Bun and jam"
        # Tokenized Candidate = ["Bread and butter", "Bun and cream"]
        # Tokenized Reference = ["Bread and butter", "Bun and jam"]
        # Matches => "Bread and butter"
        # Precision = 1/2 = 0.5
        # Recall = 1/2 = 0.5
        # F1 = 2 * (0.5 * 0.5) / (0.5 + 0.5) = 0.5
        (
            "Bread and butter, Bun and cream",
            "Bread and butter, Bun and jam",
            0.49,
            0.51,
            CustomTokenizer(delimiter=", "),
        ),
    ],
)
def test_rouge_score_using_custom_tokenizer(
    candidate, reference, expected_min, expected_max, tokenizer
):
    metric = rouge.ROUGE(tokenizer=tokenizer, track=False)
    result = metric.score(output=candidate, reference=reference)
    assert isinstance(result, ScoreResult)

    assert expected_min <= result.value <= expected_max, (
        f"For candidate='{candidate}' vs reference='{reference}', "
        f"expected rouge1 score in [{expected_min}, {expected_max}], got {result.value:.4f}"
    )
