from contextlib import asynccontextmanager, contextmanager
from types import SimpleNamespace

from opik import logging_messages, exceptions
from opik.evaluation.metrics.llm_judges.g_eval import parser
from opik.evaluation.metrics.llm_judges.g_eval.metric import GEval
from opik.evaluation.models import base_model
import pytest


def test_g_eval__parse_model_output_string__score_out_of_range__MetricComputationErrorRaised():
    invalid_model_output = (
        '{"g_eval_score": 1.8, "reason": "Score exceeds valid range."}'  # Score > 1.0
    )

    with pytest.raises(
        exceptions.MetricComputationError,
        match=logging_messages.GEVAL_SCORE_CALC_FAILED,
    ):
        parser.parse_model_output_string(
            content=invalid_model_output,
            metric_name="g_eval",
        )


# --- deterministic logprob stubs -------------------------------------------
# Shape mirrors what parse_litellm_model_output normalises via _to_dict.


def _entry(token, logprob, top=None):
    return {
        "token": token,
        "logprob": logprob,
        "top_logprobs": top
        if top is not None
        else [{"token": token, "logprob": logprob}],
    }


def _response(content, entries):
    return SimpleNamespace(
        choices=[
            SimpleNamespace(
                message={"content": content},
                logprobs={"content": entries},
            )
        ]
    )


def test_two_digit_score_split_across_tokens_scores_near_one():
    # {"score":10} tokenizes as {" score ": "1" "0" ...}; the score is 10 but
    # position 3 only sees the first digit "1", so the weighted average of
    # digit candidates lands near 0.1 instead of 1.0.
    entries = [
        _entry('{"', -0.01),
        _entry("score", -0.01),
        _entry('":', -0.01),
        _entry(
            "1",
            -0.05,
            top=[
                {"token": "1", "logprob": -0.05},
                {"token": "0", "logprob": -2.30},
                {"token": "2", "logprob": -2.40},
            ],
        ),
        _entry(
            "0",
            -0.02,
            top=[{"token": "0", "logprob": -0.02}, {"token": "1", "logprob": -3.00}],
        ),
        _entry(",", -0.01),
        _entry(" ", -0.01),
        _entry('"', -0.01),
        _entry("reason", -0.01),
        _entry('":', -0.01),
        _entry(" ", -0.01),
        _entry('"excellent"', -0.01),
        _entry("}", -0.01),
    ]
    result = parser.parse_litellm_model_output(
        _response('{"score":10, "reason": "excellent"}', entries),
        name="g_eval",
        log_probs_supported=True,
    )
    assert result.value > 0.9, f"score 10 parsed as {result.value}"


def test_leading_space_score_token_is_scored_not_rejected():
    # {"score": 0} tokenizes the space into the score token (" 0"); the
    # candidate filter rejects every candidate (none isdecimal) and the
    # chosen-token fallback raises on " 0" as well.
    entries = [
        _entry('{"', -0.01),
        _entry("score", -0.01),
        _entry('":', -0.01),
        _entry(
            " 0",
            -0.02,
            top=[
                {"token": " 0", "logprob": -0.02},
                {"token": " 1", "logprob": -2.00},
                {"token": " 10", "logprob": -2.50},
            ],
        ),
        _entry(",", -0.01),
        _entry(" ", -0.01),
        _entry('"', -0.01),
        _entry("reason", -0.01),
        _entry('":', -0.01),
        _entry(" ", -0.01),
        _entry('"none"', -0.01),
        _entry("}", -0.01),
    ]
    result = parser.parse_litellm_model_output(
        _response('{"score": 0, "reason": "none"}', entries),
        name="g_eval",
        log_probs_supported=True,
    )
    assert 0.0 < result.value < 0.09, f"unexpected value {result.value}"


def test_punctuation_carrying_split_digits_degrade_to_text_path(capture_log_debug):
    # {"score":10} split as "1" + "0,": no candidate passes the decimal
    # filter, so there is no probability mass. The parser must degrade to
    # the text path (score 1.0) instead of raising on a parseable response.
    entries = [
        _entry('{"', -0.01),
        _entry("score", -0.01),
        _entry('":', -0.01),
        _entry("1", -0.05, top=[{"token": "1,", "logprob": -0.05}]),
        _entry("0,", -0.02, top=[{"token": "0,", "logprob": -0.02}]),
        _entry(",", -0.01),
        _entry(" ", -0.01),
        _entry('"', -0.01),
        _entry("reason", -0.01),
        _entry('":', -0.01),
        _entry(" ", -0.01),
        _entry('"ok"', -0.01),
        _entry("}", -0.01),
    ]
    result = parser.parse_litellm_model_output(
        _response('{"score":10, "reason": "ok"}', entries),
        name="g_eval",
        log_probs_supported=True,
    )
    assert result.value == 1.0
    assert result.reason == "ok"
    # The degradation must stay observable: this branch found the score key, so
    # a silent fallback reads the same as a provider that returns no logprobs.
    assert "carried no probability mass" in capture_log_debug.text
    assert "'g_eval'" in capture_log_debug.text


def test_single_digit_score_at_position_three_unchanged():
    # Control: the no-space, single-digit case must keep today's exact value.
    entries = [
        _entry('{"', -0.01),
        _entry("score", -0.01),
        _entry('":', -0.01),
        _entry(
            "7",
            -0.1,
            top=[{"token": "7", "logprob": -0.1}, {"token": "1", "logprob": -2.0}],
        ),
        _entry(",", -0.01),
        _entry(" ", -0.01),
        _entry('"', -0.01),
        _entry("reason", -0.01),
        _entry('":', -0.01),
        _entry(" ", -0.01),
        _entry('"ok"', -0.01),
        _entry("}", -0.01),
    ]
    result = parser.parse_litellm_model_output(
        _response('{"score":7, "reason": "ok"}', entries),
        name="g_eval",
        log_probs_supported=True,
    )
    assert 0.60 < result.value < 0.65


def test_duplicate_score_keys_last_wins_like_the_text_path():
    # json.loads (and therefore the no-logprob text path) resolves
    # duplicate "score" keys to the LAST one. The token-stream locator
    # must mirror that: a first-occurrence scan scores from the first,
    # stale key instead.
    entries = [
        _entry('{"', -0.01),
        _entry("score", -0.01),
        _entry('":', -0.01),
        _entry(" ", -0.01),
        _entry(
            "9",
            -0.5,
            top=[{"token": "9", "logprob": -0.01}, {"token": "5", "logprob": -3.00}],
        ),
        _entry(",", -0.01),
        _entry(" ", -0.01),
        _entry('"score', -0.01),
        _entry('":', -0.01),
        _entry(" ", -0.01),
        _entry(
            "7",
            -0.1,
            top=[{"token": "7", "logprob": -0.1}, {"token": "1", "logprob": -2.00}],
        ),
        _entry(",", -0.01),
        _entry(" ", -0.01),
        _entry('"', -0.01),
        _entry("reason", -0.01),
        _entry('":', -0.01),
        _entry(" ", -0.01),
        _entry('"ok"', -0.01),
        _entry("}", -0.01),
    ]
    content = '{"score": 9, "score": 7, "reason": "ok"}'
    result = parser.parse_litellm_model_output(
        _response(content, entries),
        name="g_eval",
        log_probs_supported=True,
    )
    # Exact weighted value of the second key's candidates (7 @ -0.1,
    # 1 @ -2.0): (7*e^-0.1 + e^-2)/(e^-0.1 + e^-2)/10. A first-match locator
    # scores the stale "9" key instead (0.88084...) and fails here.
    assert result.value == pytest.approx(0.6219349153822014, abs=1e-9), (
        f"scored from the stale key: {result.value}"
    )


def test_escaped_quote_echo_is_not_a_matchable_key():
    # Control: a rubric quote inside the reason has escaped quotes, so
    # the literal "score": n there can never match the key regex -- the
    # locator stays on the real key. Guards against a looser rewrite
    # that would scan bare text.
    entries = [
        _entry('{"', -0.01),
        _entry("reason", -0.01),
        _entry('":', -0.01),
        _entry(' "the rubric said \\"', -0.01),
        _entry("score", -0.01),
        _entry('\\": ', -0.01),
        _entry(
            "9",
            -0.5,
            top=[{"token": "9", "logprob": -0.01}, {"token": "5", "logprob": -3.00}],
        ),
        _entry(' here", ', -0.01),
        _entry('"score', -0.01),
        _entry('":', -0.01),
        _entry(" ", -0.01),
        _entry(
            "7",
            -0.1,
            top=[{"token": "7", "logprob": -0.1}, {"token": "1", "logprob": -2.00}],
        ),
        _entry("}", -0.01),
    ]
    content = '{"reason": "the rubric said \\"score\\": 9 here", "score": 7}'
    result = parser.parse_litellm_model_output(
        _response(content, entries),
        name="g_eval",
        log_probs_supported=True,
    )
    # Same distribution as the duplicate-key test pins the locator on the
    # real key; see the derivation there.
    assert result.value == pytest.approx(0.6219349153822014, abs=1e-9), (
        f"unexpected value {result.value}"
    )


def test_nested_score_key_does_not_capture_the_located_token():
    # A per-criterion breakdown object carries its own "score". It comes later
    # in the stream than the top-level key, so a purely positional scan locates
    # the nested digits and weights the average from them -- while json.loads,
    # and therefore the text path, read the top-level 7. Before the value check
    # this scored 0.27397830512740046 (the nested 3's distribution).
    entries = [
        _entry('{"', -0.01),
        _entry("score", -0.01),
        _entry('":', -0.01),
        _entry(
            "7",
            -0.1,
            top=[{"token": "7", "logprob": -0.1}, {"token": "1", "logprob": -2.0}],
        ),
        _entry(", ", -0.01),
        _entry('"reason', -0.01),
        _entry('": ', -0.01),
        _entry('"ok"', -0.01),
        _entry(", ", -0.01),
        _entry('"breakdown"', -0.01),
        _entry(": {", -0.01),
        _entry('"score"', -0.01),
        _entry(": ", -0.01),
        _entry(
            "3",
            -0.1,
            top=[{"token": "3", "logprob": -0.1}, {"token": "1", "logprob": -2.0}],
        ),
        _entry("}}", -0.01),
    ]
    content = '{"score":7, "reason": "ok", "breakdown": {"score":3}}'
    result = parser.parse_litellm_model_output(
        _response(content, entries),
        name="g_eval",
        log_probs_supported=True,
    )
    # Same derivation as the duplicate-key test: the top-level key's
    # candidates are 7 @ -0.1 and 1 @ -2.0.
    assert result.value == pytest.approx(0.6219349153822014, abs=1e-9), (
        f"scored from the nested key: {result.value}"
    )


def test_nested_score_key_with_the_same_value_does_not_win_the_locator():
    # The previous test differs in value, so comparing against the parsed
    # score is enough to reject the nested key. A breakdown whose criteria all
    # agree with the total restates the same digit, so value agreement holds
    # for both positions and only depth identifies the top-level one. Here the
    # two tokens carry different distributions, so the wrong position yields a
    # plausible score that matches neither `main` nor the text path: measured
    # on `0e48cce` this returned 0.7809998433984686 (the nested distribution)
    # where `main` returns 0.6219349153822014.
    entries = [
        _entry('{"', -0.01),
        _entry("score", -0.01),
        _entry('":', -0.01),
        _entry(
            "7",
            -0.1,
            top=[{"token": "7", "logprob": -0.1}, {"token": "1", "logprob": -2.0}],
        ),
        _entry(", ", -0.01),
        _entry('"reason', -0.01),
        _entry('": ', -0.01),
        _entry('"ok"', -0.01),
        _entry(", ", -0.01),
        _entry('"breakdown"', -0.01),
        _entry(": {", -0.01),
        _entry('"score"', -0.01),
        _entry(": ", -0.01),
        _entry(
            "7",
            -0.05,
            top=[{"token": "7", "logprob": -1.5}, {"token": "8", "logprob": -0.05}],
        ),
        _entry("}}", -0.01),
    ]
    content = '{"score":7, "reason": "ok", "breakdown": {"score":7}}'
    assert parser._locate_score_entries(entries) == [3]
    result = parser.parse_litellm_model_output(
        _response(content, entries),
        name="g_eval",
        log_probs_supported=True,
    )
    assert result.value == pytest.approx(0.6219349153822014, abs=1e-9), (
        f"weighted from the nested key's tokens: {result.value}"
    )


def test_unparseable_reconstruction_keeps_the_positional_match():
    # A partial token stream reconstructs to text that is not valid JSON, so
    # there is no parsed value to agree with. That must not turn a locatable
    # score into a failed metric.
    entries = [
        _entry('{"', -0.01),
        _entry("score", -0.01),
        _entry('":', -0.01),
        _entry(
            "7",
            -0.1,
            top=[{"token": "7", "logprob": -0.1}, {"token": "1", "logprob": -2.0}],
        ),
        _entry(", ", -0.01),
    ]
    content = '{"score":7, "reason": "ok"}'
    result = parser.parse_litellm_model_output(
        _response(content, entries),
        name="g_eval",
        log_probs_supported=True,
    )
    assert result.value == pytest.approx(0.6219349153822014, abs=1e-9), (
        f"unexpected value {result.value}"
    )


# --- provider-shaped GEval coverage ----------------------------------------
# The tests above call parse_litellm_model_output directly with hand-built
# token dicts. These go through the public GEval.score LiteLLM branch with
# a provider-shaped raw response, so an incorrectly bounded result on the
# real path cannot pass unnoticed.


def _provider_response(content, entries):
    """Provider-shaped raw LiteLLM response: a dict-style choice carrying the
    message content plus the logprobs token stream, as yielded by
    get_provider_response on the LiteLLM branch."""
    return SimpleNamespace(
        choices=[{"message": {"content": content}, "logprobs": {"content": entries}}]
    )


def _geval_on_stubbed_provider(monkeypatch, content, entries):
    """GEval wired to a stubbed LiteLLM provider: chain-of-thought generation
    is stubbed, and the scoring call yields the given provider-shaped
    response. Returns (metric, captured_provider_kwargs)."""
    metric = GEval(
        task_introduction="provider-path split-score intro",
        evaluation_criteria="provider-path split-score criteria",
        model="gpt-4o",
        track=False,
    )
    # The subject here is the logprob parser branch, not model capability
    # detection (covered by test_geval_passes_logprobs_only_when_supported).
    metric._log_probs_supported = True
    monkeypatch.setattr(
        metric._model,
        "generate_chat_completion",
        lambda *args, **kwargs: {"content": "stub chain of thought"},
    )

    async def fake_agenerate_chat_completion(*args, **kwargs):
        return {"content": "stub chain of thought"}

    monkeypatch.setattr(
        metric._model,
        "agenerate_chat_completion",
        fake_agenerate_chat_completion,
    )
    captured = {}

    @contextmanager
    def fake_get_provider_response(model_provider, messages, **kwargs):
        captured.update(kwargs)
        yield _provider_response(content, entries)

    monkeypatch.setattr(base_model, "get_provider_response", fake_get_provider_response)

    @asynccontextmanager
    async def fake_aget_provider_response(model_provider, messages, **kwargs):
        captured.update(kwargs)
        yield _provider_response(content, entries)

    monkeypatch.setattr(
        base_model, "aget_provider_response", fake_aget_provider_response
    )
    return metric, captured


# Shared fixtures for the provider-shaped GEval tests below: the split-score
# and folded-whitespace token streams, each paired with the content they
# decode to and the exact value the shared synchronous parser yields.
_SPLIT_SCORE_ENTRIES = [
    _entry('{"', -0.01),
    _entry("score", -0.01),
    _entry('":', -0.01),
    _entry(
        "1",
        -0.05,
        top=[
            {"token": "1", "logprob": -0.05},
            {"token": "0", "logprob": -2.30},
            {"token": "2", "logprob": -2.40},
        ],
    ),
    _entry(
        "0",
        -0.02,
        top=[{"token": "0", "logprob": -0.02}, {"token": "1", "logprob": -3.00}],
    ),
    _entry(",", -0.01),
    _entry('"reason"', -0.01),
    _entry('":', -0.01),
    _entry(" ", -0.01),
    _entry('"excellent"', -0.01),
    _entry("}", -0.01),
]

_FOLDED_WHITESPACE_ENTRIES = [
    _entry('{"', -0.01),
    _entry("score", -0.01),
    _entry('":', -0.01),
    _entry(
        " 0",
        -0.02,
        top=[
            {"token": " 0", "logprob": -0.02},
            {"token": " 1", "logprob": -2.00},
            {"token": " 10", "logprob": -2.50},
        ],
    ),
    _entry(",", -0.01),
    _entry('"reason"', -0.01),
    _entry('":', -0.01),
    _entry(" ", -0.01),
    _entry('"none"', -0.01),
    _entry("}", -0.01),
]

_PROVIDER_CASES = [
    (
        _SPLIT_SCORE_ENTRIES,
        '{"score":10, "reason": "excellent"}',
        0.9007723389857002,
        "excellent",
    ),
    (
        _FOLDED_WHITESPACE_ENTRIES,
        '{"score": 0, "reason": "none"}',
        0.07984052568223204,
        "none",
    ),
]


@pytest.mark.parametrize(
    "entries, content, expected_value, expected_reason", _PROVIDER_CASES
)
def test_geval_litellm_path_scores_end_to_end(
    monkeypatch, entries, content, expected_value, expected_reason
):
    # The split-score case ({"score":10} across "1" + "0") must score near 1.0
    # end to end: a legacy fixed-offset read of entries[3] alone averages to
    # ~0.099. The folded-whitespace twin (the space after the colon folds into
    # the score token, " 0") must score instead of raising on a perfectly
    # parseable response.
    metric, captured = _geval_on_stubbed_provider(monkeypatch, content, entries)

    result = metric.score("any input")

    assert captured["logprobs"] is True
    assert captured["top_logprobs"] == 20
    assert result.value == pytest.approx(expected_value, abs=1e-9)
    assert result.reason == expected_reason


# --- async provider-shaped GEval coverage ------------------------------------
# GEval.ascore goes through aget_provider_response (an independent call site)
# into the same synchronous parser, so the sync test above cannot catch an
# async-only regression. Expectations mirror the sync cases exactly: identical
# entries/content through the shared parser must yield identical values.


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "entries, content, expected_value, expected_reason", _PROVIDER_CASES
)
async def test_geval_litellm_path_scores_end_to_end_async(
    monkeypatch, entries, content, expected_value, expected_reason
):
    # Async twin of test_geval_litellm_path_scores_end_to_end.
    metric, captured = _geval_on_stubbed_provider(monkeypatch, content, entries)

    result = await metric.ascore("any input")

    assert captured["logprobs"] is True
    assert captured["top_logprobs"] == 20
    assert result.value == pytest.approx(expected_value, abs=1e-9)
    assert result.reason == expected_reason
