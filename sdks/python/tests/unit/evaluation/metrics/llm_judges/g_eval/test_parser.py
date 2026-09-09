from contextlib import contextmanager
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
    from types import SimpleNamespace

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
        _entry('{\"', -0.01),
        _entry("score", -0.01),
        _entry('\":', -0.01),
        _entry(" ", -0.01),
        _entry("9", -0.5, top=[{"token": "9", "logprob": -0.01}, {"token": "5", "logprob": -3.00}]),
        _entry(",", -0.01),
        _entry(" ", -0.01),
        _entry("\"score", -0.01),
        _entry('\":', -0.01),
        _entry(" ", -0.01),
        _entry("7", -0.1, top=[{"token": "7", "logprob": -0.1}, {"token": "1", "logprob": -2.00}]),
        _entry(",", -0.01),
        _entry(" ", -0.01),
        _entry("\"", -0.01),
        _entry("reason", -0.01),
        _entry('\":', -0.01),
        _entry(" ", -0.01),
        _entry("\"ok\"", -0.01),
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
        _entry('{\"', -0.01),
        _entry("reason", -0.01),
        _entry('\":', -0.01),
        _entry(' "the rubric said \\"', -0.01),
        _entry("score", -0.01),
        _entry('\\": ', -0.01),
        _entry("9", -0.5, top=[{"token": "9", "logprob": -0.01}, {"token": "5", "logprob": -3.00}]),
        _entry(" here\", ", -0.01),
        _entry("\"score", -0.01),
        _entry('\":', -0.01),
        _entry(" ", -0.01),
        _entry("7", -0.1, top=[{"token": "7", "logprob": -0.1}, {"token": "1", "logprob": -2.00}]),
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
    captured = {}

    @contextmanager
    def fake_get_provider_response(model_provider, messages, **kwargs):
        captured.update(kwargs)
        yield _provider_response(content, entries)

    monkeypatch.setattr(base_model, "get_provider_response", fake_get_provider_response)
    return metric, captured


def test_geval_litellm_path_split_score_scores_near_one(monkeypatch):
    # {"score":10} split across "1" + "0" must score near 1.0 end to end. A
    # legacy fixed-offset read of entries[3] alone averages to ~0.099.
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
        _entry('"reason"', -0.01),
        _entry('":', -0.01),
        _entry(" ", -0.01),
        _entry('"excellent"', -0.01),
        _entry("}", -0.01),
    ]
    content = '{"score":10, "reason": "excellent"}'
    metric, captured = _geval_on_stubbed_provider(monkeypatch, content, entries)

    result = metric.score("any input")

    assert captured["logprobs"] is True
    assert captured["top_logprobs"] == 20
    assert result.value == pytest.approx(0.9007723389857002, abs=1e-9)
    assert result.reason == "excellent"


def test_geval_litellm_path_folded_whitespace_is_scored(monkeypatch):
    # The space after the colon folds into the score token (" 0"); the
    # end-to-end path must score it instead of raising
    # MetricComputationError on a perfectly parseable response.
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
        _entry('"reason"', -0.01),
        _entry('":', -0.01),
        _entry(" ", -0.01),
        _entry('"none"', -0.01),
        _entry("}", -0.01),
    ]
    content = '{"score": 0, "reason": "none"}'
    metric, captured = _geval_on_stubbed_provider(monkeypatch, content, entries)

    result = metric.score("any input")

    assert captured["logprobs"] is True
    assert captured["top_logprobs"] == 20
    assert result.value == pytest.approx(0.07984052568223204, abs=1e-9)
    assert result.reason == "none"
