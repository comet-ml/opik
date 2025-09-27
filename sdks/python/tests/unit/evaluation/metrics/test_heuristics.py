import pytest

from opik.exceptions import MetricComputationError
from opik.evaluation.metrics.heuristics import (
    equals,
    levenshtein_ratio,
    regex_match,
    rouge,
)
from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.metrics.heuristics.bleu import SentenceBLEU, CorpusBLEU
from opik.evaluation.metrics.heuristics.distribution_metrics import (
    JSDivergence,
    JSDistance,
    KLDivergence,
)
from opik.evaluation.metrics.heuristics.blanc import BLANC
from opik.evaluation.metrics.heuristics.blonde import BLONDE
from opik.evaluation.metrics.heuristics.meteor import METEOR
from opik.evaluation.metrics.heuristics.gleu import GLEU
from opik.evaluation.metrics.heuristics.bertscore import BERTScore
from opik.evaluation.metrics.heuristics.readability import ReadabilityGuard
from opik.evaluation.metrics.heuristics.tone import ToneGuard
from opik.evaluation.metrics.heuristics.supert import SUPERT
from opik.evaluation.metrics.conversation.rouge_c.metric import RougeCMetric
from opik.evaluation.metrics.conversation.bleu_c.metric import BleuCMetric
from opik.evaluation.metrics.conversation.meteor_c.metric import MeteorCMetric


class CustomTokenizer:
    def __init__(self, delimiter=" "):
        self.delimiter = delimiter

    def tokenize(self, text):
        return text.split(self.delimiter)


def test_evaluation__equals():
    metric_param = "some metric"
    metric = equals.Equals(case_sensitive=True, track=False)

    assert metric.score(output=metric_param, reference=metric_param) == ScoreResult(
        name=metric.name, value=1.0, reason=None, metadata=None
    )
    assert metric.score(output=metric_param, reference="another value") == ScoreResult(
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


class _StubBlancModel:
    def __init__(self, score: float = 0.5):
        self._score = score
        self.last_call = None

    def eval_once(self, *, summary: str, source: str) -> float:
        self.last_call = {"summary": summary, "source": source}
        return self._score


def test_blanc_score_uses_backend_stub():
    stub = _StubBlancModel(score=0.42)
    metric = BLANC(blanc_model=stub, track=False)

    result = metric.score(output="short summary", reference="full document text")

    assert isinstance(result, ScoreResult)
    assert result.value == pytest.approx(0.42)
    assert stub.last_call == {
        "summary": "short summary",
        "source": "full document text",
    }


def test_blanc_rejects_empty_inputs():
    stub = _StubBlancModel()
    metric = BLANC(blanc_model=stub, track=False)

    with pytest.raises(MetricComputationError):
        metric.score(output="   ", reference="text")

    with pytest.raises(MetricComputationError):
        metric.score(output="summary", reference="")


class _StubBlondeFn:
    def __init__(self, result):
        self._result = result
        self.calls = []

    def __call__(self, prediction, references):
        self.calls.append((prediction, tuple(references)))
        return self._result


def test_blonde_metric_with_stub():
    stub = _StubBlondeFn({"blonde": 0.61, "precision": 0.7})
    metric = BLONDE(scorer_fn=stub, track=False)

    res = metric.score(output="summary", reference=["reference"])

    assert res.value == pytest.approx(0.61)
    assert res.metadata["precision"] == pytest.approx(0.7)
    assert stub.calls == [("summary", ("reference",))]


def test_blonde_rejects_empty_inputs():
    metric = BLONDE(scorer_fn=lambda pred, refs: 0.0, track=False)
    with pytest.raises(MetricComputationError):
        metric.score(output="", reference="ref")
    with pytest.raises(MetricComputationError):
        metric.score(output="pred", reference=[""])


def test_meteor_metric_with_custom_fn():
    captured = []

    def meteor_fn(references, hypothesis):
        captured.append((tuple(references), hypothesis))
        return 0.88

    metric = METEOR(meteor_fn=meteor_fn, track=False)
    res = metric.score(output="hello world", reference="hello world")

    assert res.value == pytest.approx(0.88)
    assert captured == [(('hello world',), 'hello world')]


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


def test_readability_guard_pass_and_fail():
    guard = ReadabilityGuard(min_grade=4, max_grade=8, track=False)

    easy_text = "We can help you today. Please call me."  # short, low grade level
    hard_text = (
        "Pursuant to the aforementioned clause, fiduciary responsibilities"
        " shall be irrevocably devolved."
    )

    assert guard.score(output=easy_text).value == 1.0
    assert guard.score(output=hard_text).value == 0.0


def test_tone_guard_detects_shouting_and_negativity():
    guard = ToneGuard(track=False, max_exclamations=1, max_upper_ratio=0.2)

    polite = "Thanks for your patience. I'm happy to help you resolve this."
    rude = "THIS IS TERRIBLE!!! YOU ARE USELESS!!!"

    assert guard.score(output=polite).value == 1.0
    assert guard.score(output=rude).value == 0.0

class _StubSupertModel:
    def __init__(self, value: float = 0.35):
        self._value = value
        self.calls = []

    def score(self, *, summary: str, document: str) -> float:
        self.calls.append((summary, document))
        return self._value


def test_supert_score_uses_backend():
    stub = _StubSupertModel(value=0.73)
    metric = SUPERT(supert_model=stub, track=False)

    result = metric.score(output="summary", reference="document")

    assert isinstance(result, ScoreResult)
    assert result.value == pytest.approx(0.73)
    assert stub.calls == [("summary", "document")]


def test_supert_backend_callable():
    calls = []

    def scorer(*, summary: str, document: str) -> float:
        calls.append((summary, document))
        return 0.55

    metric = SUPERT(supert_model=scorer, track=False)
    res = metric.score(output="summ", reference="doc")

    assert res.value == pytest.approx(0.55)
    assert calls == [("summ", "doc")]


def test_supert_rejects_empty_inputs():
    metric = SUPERT(supert_model=_StubSupertModel(), track=False)

    with pytest.raises(MetricComputationError):
        metric.score(output="", reference="doc")

    with pytest.raises(MetricComputationError):
        metric.score(output="summary", reference="   ")


class _StubRougeMetric:
    def __init__(self, scores):
        self._scores = scores
        self.calls = []

    def score(self, *, output, reference):
        self.calls.append((output, reference))
        idx = len(self.calls) - 1
        return ScoreResult(name="rouge", value=self._scores[idx])


def test_rouge_c_metric_average_and_penalty():
    rouge_stub = _StubRougeMetric(scores=[0.8, 0.6])
    metric = RougeCMetric(rouge_metric=rouge_stub, missing_turn_penalty=0.1, track=False)

    conversation = [
        {"role": "user", "content": "Hi"},
        {"role": "assistant", "content": "Hello there"},
        {"role": "assistant", "content": "How can I help?"},
    ]
    reference = [
        {"role": "user", "content": "Hi"},
        {"role": "assistant", "content": "Greetings"},
        {"role": "assistant", "content": "What can I do for you?"},
    ]

    result = metric.score(conversation=conversation, reference_conversation=reference)

    assert result.value == pytest.approx((0.8 + 0.6) / 2)
    assert rouge_stub.calls == [
        ("Hello there", "Greetings"),
        ("How can I help?", "What can I do for you?"),
    ]
    assert result.metadata["evaluated_turns"] == 2
    assert result.metadata["missing_turns"] == 0


def test_rouge_c_requires_target_turns():
    metric = RougeCMetric(rouge_metric=_StubRougeMetric(scores=[0.5]), track=False)

    with pytest.raises(MetricComputationError):
        metric.score(
            conversation=[{"role": "user", "content": "hi"}],
            reference_conversation=[{"role": "assistant", "content": "hello"}],
        )

    with pytest.raises(MetricComputationError):
        metric.score(
            conversation=[{"role": "assistant", "content": "hi"}],
            reference_conversation=[{"role": "user", "content": "hello"}],
        )


class _StubTurnMetric:
    def __init__(self, scores):
        self._scores = scores
        self.calls = []

    def score(self, *, output, reference):
        self.calls.append((output, reference))
        idx = len(self.calls) - 1
        return ScoreResult(name="stub", value=self._scores[idx])


def test_bleu_c_metric_with_stub():
    stub = _StubTurnMetric([0.4, 0.6])
    metric = BleuCMetric(bleu_metric=stub, track=False)

    convo = [
        {"role": "assistant", "content": "one"},
        {"role": "assistant", "content": "two"},
    ]
    ref = [
        {"role": "assistant", "content": "uno"},
        {"role": "assistant", "content": "dos"},
    ]

    result = metric.score(conversation=convo, reference_conversation=ref)
    assert result.value == pytest.approx(0.5)
    assert stub.calls == [("one", "uno"), ("two", "dos")]


def test_meteor_c_metric_with_stub():
    stub = _StubTurnMetric([0.3])
    metric = MeteorCMetric(meteor_metric=stub, track=False)

    convo = [{"role": "assistant", "content": "hi"}]
    ref = [{"role": "assistant", "content": "hello"}]

    result = metric.score(conversation=convo, reference_conversation=ref)
    assert result.value == pytest.approx(0.3)
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


def test_rouge_w_score_computation():
    metric = rouge.ROUGE(rouge_type="rougeW", track=False)
    result = metric.score(output="a b c", reference="a b d")
    assert 0.0 <= result.value <= 1.0


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
