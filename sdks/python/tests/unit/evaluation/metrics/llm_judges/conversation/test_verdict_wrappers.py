import itertools
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


class _ScriptedJudge(base_model.OpikBaseModel):
    """Answers the per-turn judge calls with the given verdict(s), in turn."""

    def __init__(self, verdicts):
        super().__init__(model_name="scripted-judge")
        if isinstance(verdicts, str):
            verdicts = [verdicts]
        self._replies = itertools.cycle(
            [json.dumps({"verdict": v, "reason": "judge reason"}) for v in verdicts]
        )
        self._lock = threading.Lock()

    def _next_reply(self):
        with self._lock:
            return next(self._replies)

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
        ("kind of", None),
        ("nothing", None),
        ("not sure", None),
        ("", None),
        (None, None),
    ],
)
def test_read_yes_no(verdict, expected):
    assert parsing_helpers.read_yes_no(verdict) == expected


@pytest.mark.parametrize(
    "verdict", ["no", "No.", "**No**", "`no`", '"no"', "No, off-topic"]
)
@pytest.mark.parametrize(
    "metric_cls", [ConversationalCoherenceMetric, SessionCompletenessQuality]
)
def test_negative_verdicts_score_zero(metric_cls, verdict):
    metric = metric_cls(
        model=_ScriptedJudge(verdict), include_reason=False, track=False
    )
    assert metric.score(_CONVERSATION).value == 0.0


@pytest.mark.parametrize(
    "verdict", ["yes", "Yes.", "**Yes**", "Yes, clearly frustrated"]
)
def test_user_frustration_positive_verdicts_score_one(verdict):
    metric = UserFrustrationMetric(
        model=_ScriptedJudge(verdict), include_reason=False, track=False
    )
    assert metric.score(_CONVERSATION).value == 1.0


@pytest.mark.parametrize(
    "metric_cls",
    [ConversationalCoherenceMetric, SessionCompletenessQuality, UserFrustrationMetric],
)
def test_unreadable_verdicts_fail_instead_of_scoring(metric_cls):
    metric = metric_cls(
        model=_ScriptedJudge("kind of"), include_reason=False, track=False
    )
    with pytest.raises(exceptions.MetricComputationError):
        metric.score(_CONVERSATION)


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
    ],
)
def test_unreadable_verdicts_are_left_out_of_the_denominator(
    metric_cls, verdicts, expected
):
    metric = metric_cls(
        model=_ScriptedJudge(verdicts), include_reason=False, track=False
    )
    assert metric.score(_CONVERSATION).value == expected


_SENTINEL = "SENTINEL-user-secret-4242"


@pytest.mark.parametrize(
    "metric_cls",
    [ConversationalCoherenceMetric, SessionCompletenessQuality, UserFrustrationMetric],
)
def test_unreadable_verdict_text_is_not_logged(metric_cls, caplog):
    metric = metric_cls(
        model=_ScriptedJudge(["yes", f"maybe, the user said {_SENTINEL}"]),
        include_reason=False,
        track=False,
    )
    with caplog.at_level(logging.DEBUG):
        metric.score(_CONVERSATION)
    assert "ignored 1 of 2 judge verdict(s)" in caplog.text
    assert _SENTINEL not in caplog.text


@pytest.mark.parametrize(
    "metric_cls",
    [ConversationalCoherenceMetric, SessionCompletenessQuality, UserFrustrationMetric],
)
def test_unreadable_verdict_text_is_not_in_the_error(metric_cls, caplog):
    metric = metric_cls(
        model=_ScriptedJudge(f"unsure, the user said {_SENTINEL}"),
        include_reason=False,
        track=False,
    )
    with caplog.at_level(logging.DEBUG):
        with pytest.raises(exceptions.MetricComputationError) as exc_info:
            metric.score(_CONVERSATION)
    assert _SENTINEL not in str(exc_info.value)
    assert _SENTINEL not in caplog.text
