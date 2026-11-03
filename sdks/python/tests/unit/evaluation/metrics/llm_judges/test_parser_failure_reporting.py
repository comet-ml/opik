"""When a judge parser fails closed, the exception must say why.

`parsing_helpers.extract_json_content_or_raise` raises `JSONParsingError` with
the reason (for example, two different JSON objects). The parsers used to log
that reason and then raise a fixed message with no cause, so a caller that only
reads `str(error)` or `error.__cause__` could not tell why the metric failed.
"""

import json

import pytest

from opik import exceptions, logging_messages
from opik.evaluation.metrics.llm_judges import parsing_helpers
from opik.evaluation.metrics.llm_judges.answer_relevance import (
    parser as answer_relevance_parser,
)
from opik.evaluation.metrics.llm_judges.context_precision import (
    parser as context_precision_parser,
)
from opik.evaluation.metrics.llm_judges.context_recall import (
    parser as context_recall_parser,
)
from opik.evaluation.metrics.llm_judges.factuality import parser as factuality_parser
from opik.evaluation.metrics.llm_judges.hallucination import (
    parser as hallucination_parser,
)
from opik.evaluation.metrics.llm_judges.moderation import parser as moderation_parser
from opik.evaluation.metrics.llm_judges.usefulness import parser as usefulness_parser

TOKEN_FOOTER = 'Tokens used: {"prompt": 120, "completion": 18}'

# (parser module, fixed message, score key, wraps the verdict in a list)
PARSERS = [
    pytest.param(
        answer_relevance_parser,
        logging_messages.ANSWER_RELEVANCE_SCORE_CALC_FAILED,
        "answer_relevance_score",
        False,
        id="answer_relevance",
    ),
    pytest.param(
        context_precision_parser,
        logging_messages.CONTEXT_PRECISION_SCORE_CALC_FAILED,
        "context_precision_score",
        False,
        id="context_precision",
    ),
    pytest.param(
        context_recall_parser,
        logging_messages.CONTEXT_RECALL_SCORE_CALC_FAILED,
        "context_recall_score",
        False,
        id="context_recall",
    ),
    pytest.param(
        factuality_parser,
        logging_messages.FACTUALITY_SCORE_CALC_FAILED,
        "score",
        True,
        id="factuality",
    ),
    pytest.param(
        hallucination_parser,
        logging_messages.HALLUCINATION_DETECTION_FAILED,
        "score",
        False,
        id="hallucination",
    ),
    pytest.param(
        moderation_parser,
        logging_messages.MODERATION_SCORE_CALC_FAILED,
        "score",
        False,
        id="moderation",
    ),
    pytest.param(
        usefulness_parser,
        logging_messages.USEFULNESS_SCORE_CALC_FAILED,
        "score",
        False,
        id="usefulness",
    ),
]


def _verdict(score_key: str, as_list: bool, score: float = 0.5) -> str:
    verdict = json.dumps({score_key: score, "reason": "ok"})
    return f"[{verdict}]" if as_list else verdict


def _helper_message(content: str) -> str:
    with pytest.raises(exceptions.JSONParsingError) as helper_error:
        parsing_helpers.extract_json_content_or_raise(content)
    return str(helper_error.value)


@pytest.mark.parametrize("parser,fixed_message,score_key,as_list", PARSERS)
def test_parse_model_output__ambiguous_output__message_carries_the_helper_reason(
    parser, fixed_message, score_key, as_list
):
    ambiguous = _verdict(score_key, as_list) + "\n" + TOKEN_FOOTER

    with pytest.raises(exceptions.MetricComputationError) as error:
        parser.parse_model_output(content=ambiguous, name="m")

    assert str(error.value) == f"{fixed_message}: {_helper_message(ambiguous)}"


@pytest.mark.parametrize("parser,fixed_message,score_key,as_list", PARSERS)
def test_parse_model_output__ambiguous_output__cause_is_the_helper_exception(
    parser, fixed_message, score_key, as_list
):
    ambiguous = _verdict(score_key, as_list) + "\n" + TOKEN_FOOTER

    with pytest.raises(exceptions.MetricComputationError) as error:
        parser.parse_model_output(content=ambiguous, name="m")

    assert isinstance(error.value.__cause__, exceptions.JSONParsingError)
    assert str(error.value.__cause__) == _helper_message(ambiguous)


@pytest.mark.parametrize("parser,fixed_message,score_key,as_list", PARSERS)
@pytest.mark.parametrize(
    "case", ["score_out_of_range", "missing_reason"], ids=lambda c: c
)
def test_parse_model_output__other_failures__keep_the_fixed_message_and_chain_cause(
    parser, fixed_message, score_key, as_list, case
):
    """Only a `JSONParsingError` adds its reason to the message. A missing key
    or a score out of range keeps the fixed message and gains only the cause."""
    if case == "score_out_of_range":
        content = _verdict(score_key, as_list, score=1.5)
    else:
        content = json.dumps({score_key: 0.5})
        content = f"[{content}]" if as_list else content

    with pytest.raises(exceptions.MetricComputationError) as error:
        parser.parse_model_output(content=content, name="m")

    assert str(error.value) == fixed_message
    assert error.value.__cause__ is not None
    assert not isinstance(error.value.__cause__, exceptions.JSONParsingError)


@pytest.mark.parametrize("parser,fixed_message,score_key,as_list", PARSERS)
def test_parse_model_output__single_verdict__still_scores(
    parser, fixed_message, score_key, as_list
):
    result = parser.parse_model_output(content=_verdict(score_key, as_list), name="m")

    assert result.value == 0.5
    assert result.scoring_failed is False


@pytest.mark.parametrize(
    "parser,fixed_message,score_key,as_list",
    [p for p in PARSERS if not p.values[3]],
)
def test_parse_model_output__identical_repeat__still_scores(
    parser, fixed_message, score_key, as_list
):
    verdict = _verdict(score_key, as_list)

    result = parser.parse_model_output(content=f"{verdict}\n{verdict}", name="m")

    assert result.value == 0.5
