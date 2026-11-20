import json
import logging
import threading

import pytest

from opik import exceptions
from opik.evaluation.metrics import (
    ConversationalCoherenceMetric,
    SessionCompletenessQuality,
    UserFrustrationMetric,
)
from opik.evaluation.metrics.llm_judges import parsing_helpers
from opik.evaluation.models import base_model


# Per-turn verdicts each metric asks for on _CONVERSATION: one per assistant
# turn, or one per user goal for SessionCompleteness.
_VERDICTS_PER_SCORE = 2


class _ScriptedJudge(base_model.OpikBaseModel):
    """Answers the per-turn judge calls with the given verdicts, one per call.

    A single verdict answers every per-turn call of one score. The replies run
    out: a call past the last one fails, and ``verdicts_left`` lets a test
    check that the metric asked for every scripted verdict.
    """

    def __init__(self, verdicts):
        super().__init__(model_name="scripted-judge")
        if isinstance(verdicts, str):
            verdicts = [verdicts] * _VERDICTS_PER_SCORE
        self._replies = [
            json.dumps({"verdict": v, "reason": "judge reason"}) for v in verdicts
        ]
        self._lock = threading.Lock()

    @property
    def verdicts_left(self):
        return len(self._replies)

    def _next_reply(self):
        with self._lock:
            if not self._replies:
                raise AssertionError("the metric asked for more verdicts than scripted")
            return self._replies.pop(0)

    def generate_string(self, input, response_format=None, **kwargs):
        return self._next_reply()

    def generate_provider_response(self, messages, **kwargs):
        raise NotImplementedError

    def generate_chat_completion(self, messages, response_format=None, **kwargs):
        if "goals" in getattr(response_format, "__name__", "").lower():
            # Two goals, so SessionCompleteness asks for two verdicts like
            # the two assistant turns of _CONVERSATION do for the others.
            content = json.dumps({"user_goals": ["book a flight", "on Friday"]})
        else:
            content = self._next_reply()
        return {"role": "assistant", "content": content}


_CONVERSATION = [
    {"role": "user", "content": "Book me a flight to Oslo on Friday."},
    {"role": "assistant", "content": "Bananas are rich in potassium."},
    {"role": "user", "content": "That is not what I asked. Book the flight!"},
    {"role": "assistant", "content": "I like turtles."},
]


@pytest.mark.parametrize(
    "verdict, expected",
    [
        ("yes", "yes"),
        ("No.", "no"),
        ("**No**", "no"),
        ("**yes**", "yes"),
        ("`no`", "no"),
        ('"no"', "no"),
        ("“Yes”", "yes"),
        ("No, the reply ignores the request", "no"),
        ("Yes - the goal was met", "yes"),
        # A leading-word read, not a semantic one: a self-correction after
        # the first word is not interpreted.
        ("No, correction: yes", "no"),
        ("_no_", "no"),
        ("__Yes__", "yes"),
        ("_no_, the reply is off-topic", "no"),
        ("**_yes_**", "yes"),
        ("kind of", None),
        ("nothing", None),
        ("not sure", None),
        # The verdict word must end the word, in any script.
        ("yes1", None),
        ("no123", None),
        ("no_foo", None),
        ("yes_please", None),
        ("noé", None),
        ("yesß", None),
        # Trailing underscores only count as closing emphasis.
        ("yes_", None),
        ("no__", None),
        ("yes___", None),
        ("_no__", None),
        ("__yes_", None),
        ("", None),
        (None, None),
    ],
)
def test_read_yes_no(verdict, expected):
    assert parsing_helpers.read_yes_no(verdict) == expected


def _assert_scored(metric, judge, result, expected):
    assert judge.verdicts_left == 0
    assert result.value == expected
    assert result.name == metric.name
    assert result.reason is None  # include_reason=False
    assert result.scoring_failed is False


def test_glued_verdict_words_are_left_out_of_the_share(caplog):
    verdicts = ["no", "yes1", "yes_please", "no123", "noé"]
    with caplog.at_level(logging.WARNING):
        share = parsing_helpers.yes_share_of_readable_verdicts(
            verdicts, metric_name="some_metric"
        )
    assert share == 0.0
    assert "ignored 4 of 5 judge verdict(s)" in caplog.text
    assert "[2, 3, 4, 5]" in caplog.text


def test_only_glued_verdict_words_fail_instead_of_scoring():
    with pytest.raises(exceptions.MetricComputationError) as exc_info:
        parsing_helpers.yes_share_of_readable_verdicts(
            ["yes1", "no_foo"], metric_name="some_metric"
        )
    assert "none of the 2 judge verdict(s) reads as yes or no" in str(exc_info.value)


def test_ignored_verdict_warning_lists_bounded_one_based_positions(caplog):
    verdicts = ["yes"] + ["N/A"] * 25
    with caplog.at_level(logging.WARNING):
        share = parsing_helpers.yes_share_of_readable_verdicts(
            verdicts, metric_name="some_metric"
        )
    assert share == 1.0
    (message,) = [record.getMessage() for record in caplog.records]
    assert "ignored 25 of 26 judge verdict(s)" in message
    assert f"1-based: {list(range(2, 22))} and 5 more)" in message


@pytest.mark.parametrize(
    "verdict", ["no", "No.", "**No**", "`no`", '"no"', "No, off-topic"]
)
@pytest.mark.parametrize(
    "metric_cls", [ConversationalCoherenceMetric, SessionCompletenessQuality]
)
def test_negative_verdicts_score_zero(metric_cls, verdict):
    judge = _ScriptedJudge(verdict)
    metric = metric_cls(model=judge, include_reason=False, track=False)
    _assert_scored(metric, judge, metric.score(_CONVERSATION), 0.0)


@pytest.mark.parametrize(
    "verdict", ["yes", "Yes.", "**Yes**", "Yes, clearly frustrated"]
)
def test_user_frustration_positive_verdicts_score_one(verdict):
    judge = _ScriptedJudge(verdict)
    metric = UserFrustrationMetric(model=judge, include_reason=False, track=False)
    _assert_scored(metric, judge, metric.score(_CONVERSATION), 1.0)


@pytest.mark.parametrize(
    "metric_cls",
    [ConversationalCoherenceMetric, SessionCompletenessQuality, UserFrustrationMetric],
)
def test_unreadable_verdicts_fail_instead_of_scoring(metric_cls):
    judge = _ScriptedJudge("kind of")
    metric = metric_cls(model=judge, include_reason=False, track=False)
    with pytest.raises(exceptions.MetricComputationError) as exc_info:
        metric.score(_CONVERSATION)
    assert judge.verdicts_left == 0
    assert "none of the 2 judge verdict(s) reads as yes or no" in str(exc_info.value)


@pytest.mark.parametrize(
    "metric_cls, verdicts, expected",
    [
        # Unreadable verdicts are left out of the denominator on purpose:
        # main scores these 0.5 because "N/A" counts as positive / ignored.
        (ConversationalCoherenceMetric, ["no", "N/A"], 0.0),
        (SessionCompletenessQuality, ["no", "N/A"], 0.0),
        (ConversationalCoherenceMetric, ["yes", "N/A"], 1.0),
        (UserFrustrationMetric, ["yes", "N/A"], 1.0),
        (UserFrustrationMetric, ["no", "N/A"], 0.0),
        # A verdict word glued to more letters or digits is unreadable too.
        (ConversationalCoherenceMetric, ["no", "yes1"], 0.0),
        (SessionCompletenessQuality, ["no", "yes_please"], 0.0),
        (UserFrustrationMetric, ["no", "yesé"], 0.0),
        (ConversationalCoherenceMetric, ["no", "yes_"], 0.0),
    ],
)
def test_unreadable_verdicts_are_left_out_of_the_denominator(
    metric_cls, verdicts, expected
):
    judge = _ScriptedJudge(verdicts)
    metric = metric_cls(model=judge, include_reason=False, track=False)
    _assert_scored(metric, judge, metric.score(_CONVERSATION), expected)


_SENTINEL = "SENTINEL-user-secret-4242"


@pytest.mark.parametrize(
    "metric_cls",
    [ConversationalCoherenceMetric, SessionCompletenessQuality, UserFrustrationMetric],
)
def test_unreadable_verdict_text_is_not_logged(metric_cls, caplog):
    judge = _ScriptedJudge(["yes", f"maybe, the user said {_SENTINEL}"])
    metric = metric_cls(model=judge, include_reason=False, track=False)
    with caplog.at_level(logging.DEBUG):
        result = metric.score(_CONVERSATION)
    _assert_scored(metric, judge, result, 1.0)
    assert "ignored 1 of 2 judge verdict(s)" in caplog.text
    assert "1-based: [2]" in caplog.text
    assert _SENTINEL not in caplog.text


@pytest.mark.parametrize(
    "metric_cls",
    [ConversationalCoherenceMetric, SessionCompletenessQuality, UserFrustrationMetric],
)
def test_unreadable_verdict_text_is_not_in_the_error(metric_cls, caplog):
    judge = _ScriptedJudge(f"unsure, the user said {_SENTINEL}")
    metric = metric_cls(model=judge, include_reason=False, track=False)
    with caplog.at_level(logging.DEBUG):
        with pytest.raises(exceptions.MetricComputationError) as exc_info:
            metric.score(_CONVERSATION)
    assert judge.verdicts_left == 0
    assert "none of the 2 judge verdict(s) reads as yes or no" in str(exc_info.value)
    assert _SENTINEL not in str(exc_info.value)
    assert _SENTINEL not in caplog.text
