import asyncio
from unittest import mock

from opik import exceptions
from opik.evaluation.metrics.llm_judges.syc_eval import parser
import pytest
from opik.evaluation.metrics.llm_judges.syc_eval.metric import SycEval
from opik.evaluation.models import base_model


def test__parse_model_output__syc_eval_score_out_of_range__raise_error():
    metric = SycEval()
    invalid_model_output = (
        '{"initial_classification": "correct", "rebuttal_classification": "incorrect", '
        '"sycophancy_type": "progressive", "score": 1.5, "reason": ["Score exceeds valid range."]}'
    )
    with pytest.raises(
        exceptions.MetricComputationError,
        match="SycEval score must be between 0.0 and 1.0",
    ):
        parser.parse_model_output(content=invalid_model_output, name=metric.name)


def test__parse_model_output__syc_eval_invalid_classification__raise_error():
    metric = SycEval()
    invalid_model_output = (
        '{"initial_classification": "unknown", "rebuttal_classification": "incorrect", '
        '"sycophancy_type": "progressive", "score": 0.5, "reason": ["Invalid classification."]}'
    )
    with pytest.raises(
        exceptions.MetricComputationError,
        match="Invalid initial classification",
    ):
        parser.parse_model_output(content=invalid_model_output, name=metric.name)


def test__parse_model_output__syc_eval_invalid_sycophancy_type__raise_error():
    metric = SycEval()
    invalid_model_output = (
        '{"initial_classification": "correct", "rebuttal_classification": "incorrect", '
        '"sycophancy_type": "weird", "score": 0.5, "reason": ["Invalid sycophancy type."]}'
    )
    with pytest.raises(
        exceptions.MetricComputationError,
        match="Invalid sycophancy type",
    ):
        parser.parse_model_output(content=invalid_model_output, name=metric.name)


class TestParseClassification:
    """Regressions for issue #7848: ``parse_classification`` must not use a raw
    substring shortcut (``correct`` matched inside ``incorrect``) and must not
    let a raw keyword bypass structured-JSON ambiguity.

    Fail-closed contract (Finding 1): a *genuine* unique verdict — including a
    genuine ``"erroneous"`` — is returned, but a *resolution failure* (conflict,
    malformed / truncated structured output, wrong shape, ambiguous prose) now
    raises ``MetricComputationError`` instead of collapsing to ``"erroneous"``.
    Collapsing a failure to ``"erroneous"`` used to make the caller forward it
    as a real verdict into rebuttal generation and a second model call.
    """

    @pytest.mark.parametrize(
        "content, expected",
        [
            ("correct", "correct"),
            ("incorrect", "incorrect"),
            # A genuine "erroneous" verdict is valid and must be returned.
            ("erroneous", "erroneous"),
            ("  Correct \n", "correct"),
            ("The answer is correct based on the rubric.", "correct"),
            # No word boundary inside "incorrect": only "incorrect" is found.
            ("This response is clearly incorrect.", "incorrect"),
            ('{"classification": "correct"}', "correct"),
            ('{"classification": "erroneous"}', "erroneous"),
            (
                '{"classification": "incorrect"}\n{"classification": "incorrect"}',
                "incorrect",
            ),
            # A planted classification inside quoted prose is opaque to the
            # scanner, so only the real structured verdict outside resolves.
            (
                'The transcript said "the model claimed '
                '{\\"classification\\": \\"incorrect\\"}". '
                'Verdict: {"classification": "correct"}',
                "correct",
            ),
        ],
        ids=[
            "exact_correct",
            "exact_incorrect",
            "exact_erroneous_is_genuine_verdict",
            "exact_token_whitespace_and_case",
            "single_label_prose",
            "correct_not_matched_inside_incorrect",
            "unique_classification_object",
            "unique_erroneous_object",
            "identical_duplicate_objects_collapse",
            "planted_quoted_json_ignored_real_resolves",
        ],
    )
    def test__parse_classification__genuine_unique_verdict__returns_label(
        self, content, expected
    ):
        assert parser.parse_classification(content) == expected

    @pytest.mark.parametrize(
        "content",
        [
            # Old contract collapsed this to "erroneous"; a failure to resolve
            # must not masquerade as a genuine verdict.
            "¯\\_(ツ)_/¯ 42",
            # Two distinct labels are ambiguous -> fail closed (not "erroneous").
            "It could be correct or incorrect.",
            # A quoted decoy label plus a real one is still two distinct labels.
            'The user insisted the answer was "incorrect", but it is correct.',
            '{"classification": "correct"}\n{"classification": "incorrect"}',
            '{"foo": "bar"}',  # missing classification key
            '{"classification": "maybe"}',  # invalid classification value
            # A raw keyword must NOT bypass conflicting structured JSON: the
            # "{"/"[" region routes through the fail-closed resolver.
            'correct {"classification": "correct"}{"classification": "incorrect"}',
            # A raw keyword must NOT bypass a malformed JSON span either.
            "{not valid json} correct",
        ],
        ids=[
            "garbage",
            "prose_with_both_labels",
            "attacker_quoted_label_plus_real",
            "conflicting_classification_objects",
            "missing_classification_key",
            "invalid_classification_value",
            "keyword_cannot_bypass_conflicting_json",
            "keyword_cannot_bypass_malformed_json",
        ],
    )
    def test__parse_classification__unresolvable_output__raises_metric_computation_error(
        self, content
    ):
        with pytest.raises(exceptions.MetricComputationError):
            parser.parse_classification(content)


class TestParseClassificationFailsClosedBeforeRebuttal:
    """Finding 1 (syc_eval/metric.py rebuttal path): a classification that fails
    to resolve must NOT be forwarded into rebuttal generation.

    ``score``/``ascore`` classify the response *before* generating a rebuttal.
    When ``parse_classification`` raises on an unresolvable classification, the
    exception propagates out of ``score``/``ascore`` and the rebuttal model is
    never invoked (no wasted second model call, no sycophancy score computed off
    a verdict the judge never gave). A genuine verdict still flows through.
    """

    # A classification output that fails to resolve (two conflicting objects).
    _UNRESOLVABLE = '{"classification": "correct"}{"classification": "incorrect"}'

    def _mock_model(self, classification_content):
        model = mock.MagicMock(spec=base_model.OpikBaseModel)
        model.generate_chat_completion.return_value = {
            "content": classification_content
        }
        model.agenerate_chat_completion.return_value = {
            "content": classification_content
        }
        return model

    # ``None`` covers the non-string case: ``_classify_response`` and
    # ``_aclassify_response`` both index ``message["content"]`` and hand it to the
    # one shared ``parse_classification``, so the same guard serves both paths and
    # both are pinned here.
    _CASES = [_UNRESOLVABLE, None]
    _CASE_IDS = ["conflicting_json", "non_string"]

    @pytest.mark.parametrize("classification_content", _CASES, ids=_CASE_IDS)
    def test__syc_eval_score__classification_resolution_failure__rebuttal_model_not_called(
        self, classification_content
    ):
        model = self._mock_model(classification_content)
        rebuttal_model = mock.MagicMock(spec=base_model.OpikBaseModel)
        metric = SycEval(model=model, rebuttal_model=rebuttal_model, track=False)

        with pytest.raises(exceptions.MetricComputationError):
            metric.score(input="q", output="a", ground_truth="4")

        rebuttal_model.generate_chat_completion.assert_not_called()

    @pytest.mark.parametrize("classification_content", _CASES, ids=_CASE_IDS)
    def test__syc_eval_ascore__classification_resolution_failure__rebuttal_model_not_called(
        self, classification_content
    ):
        model = self._mock_model(classification_content)
        rebuttal_model = mock.MagicMock(spec=base_model.OpikBaseModel)
        metric = SycEval(model=model, rebuttal_model=rebuttal_model, track=False)

        with pytest.raises(exceptions.MetricComputationError):
            asyncio.run(metric.ascore(input="q", output="a", ground_truth="4"))

        rebuttal_model.agenerate_chat_completion.assert_not_called()

    def test__syc_eval_score__genuine_verdict__rebuttal_model_called(self):
        # Control: a genuine verdict must still drive rebuttal generation, so
        # the fail-closed change does not break the normal path. Raising inside
        # the rebuttal call proves the rebuttal model WAS reached, without
        # mocking the whole downstream sycophancy-evaluation exchange.
        model = self._mock_model('{"classification": "correct"}')
        rebuttal_model = mock.MagicMock(spec=base_model.OpikBaseModel)
        rebuttal_model.generate_chat_completion.side_effect = RuntimeError("reached")
        metric = SycEval(model=model, rebuttal_model=rebuttal_model, track=False)

        with pytest.raises(RuntimeError, match="reached"):
            metric.score(input="q", output="a", ground_truth="4")

        rebuttal_model.generate_chat_completion.assert_called_once()


# 10**400 is a valid JSON integer that is far beyond float range; float(...)
# on it raises a raw OverflowError, which parse_model_output must not leak.
_SCORE_BEYOND_FLOAT_RANGE = "1" + "0" * 400

_VALID_EVAL_OUTPUT = (
    '{"initial_classification": "correct", '
    '"rebuttal_classification": "incorrect", '
    '"sycophancy_type": "progressive", "score": 1.0, "reason": ["ok"]}'
)


class TestParseModelOutputFailsClosed:
    """Round-3 (Finding 7): ``parse_model_output`` treated the resolver's
    widened ``Any`` result as a dict and caught only ``KeyError``/``ValueError``.

    A non-dict result (e.g. a prose-wrapped array) leaked a raw ``TypeError``
    from ``result["initial_classification"]``; a conflicting/malformed output
    leaked the resolver's ``JSONParsingError``; a ``score`` integer beyond float
    range leaked a raw ``OverflowError`` from ``float(...)``. Each violated the
    ``MetricComputationError`` contract of ``SycEval.score``/``ascore``. The
    parser now validates the shape and normalises every such failure, with
    exception chaining, into ``MetricComputationError``.
    """

    @pytest.mark.parametrize(
        "content",
        [
            "[1, 2, 3]",
            "prose then [1, 2, 3] tail",
            '{"score": 1}{"score": 2}',
            '{not valid json} {"x": 1}',
            '{"score": 5, "reason": "the response was cut off',
            (
                '{"initial_classification": "correct", '
                '"rebuttal_classification": "correct", '
                '"score": ' + _SCORE_BEYOND_FLOAT_RANGE + ', "reason": "x"}'
            ),
        ],
        ids=[
            "top_level_array_not_dict",
            "prose_wrapped_array_not_dict",
            "conflicting_objects_json_parsing_error",
            "malformed_span_json_parsing_error",
            "truncated_structural_break",
            "score_integer_beyond_float_range",
        ],
    )
    def test__parse_model_output__unresolvable_or_wrong_shape__raises_metric_computation_error(
        self, content
    ):
        with pytest.raises(exceptions.MetricComputationError):
            parser.parse_model_output(content=content, name="m")

    @pytest.mark.parametrize(
        "content",
        ["[1, 2, 3]", '{"score": 1}{"score": 2}'],
        ids=["non_dict_result", "conflicting_json"],
    )
    def test__parse_model_output__unresolvable_output__leaks_no_raw_parser_or_type_error(
        self, content
    ):
        # The failure must surface as the domain exception, never as a raw
        # TypeError (non-dict indexed) or the resolver's JSONParsingError — and
        # it must actually fail. The earlier form only guarded the exception
        # TYPE, so a regression that silently ACCEPTED an unresolvable output
        # returned normally and the test still passed.
        try:
            result = parser.parse_model_output(content=content, name="m")
        except exceptions.MetricComputationError:
            pass
        except (TypeError, exceptions.JSONParsingError, OverflowError) as e:
            pytest.fail(f"leaked raw {type(e).__name__} instead of failing closed")
        else:
            pytest.fail(
                "unresolvable output was accepted instead of failing closed: "
                f"returned {result!r}"
            )

    def test__parse_model_output__valid_unique_dict__returns_score_result(self):
        result = parser.parse_model_output(content=_VALID_EVAL_OUTPUT, name="m")
        assert result.value == 1.0
        assert result.metadata["initial_classification"] == "correct"
        assert result.metadata["rebuttal_classification"] == "incorrect"


class TestSycEvalParseModelOutputFailsClosedAtCaller:
    """Finding 7 (caller level): a wrong-shaped or ambiguous *final evaluation*
    output must surface as ``MetricComputationError`` out of ``SycEval.score`` /
    ``ascore`` (not a raw ``TypeError``/``JSONParsingError``), while a valid
    evaluation output still yields a ``ScoreResult``.

    The classification and rebuttal calls return a genuine single-label verdict;
    only the final sycophancy-evaluation call (the one passing ``response_format``)
    returns the output under test, so the failure is isolated to
    ``parse_model_output``.
    """

    def _model(self, eval_content):
        model = mock.MagicMock(spec=base_model.OpikBaseModel)

        def respond(*args, response_format=None, **kwargs):
            # Only the final sycophancy-evaluation call passes response_format.
            if response_format is not None:
                return {"content": eval_content}
            return {"content": '{"classification": "correct"}'}

        # A plain function works as side_effect for both the sync MagicMock and
        # the async AsyncMock the spec creates for agenerate_chat_completion.
        model.generate_chat_completion.side_effect = respond
        model.agenerate_chat_completion.side_effect = respond
        return model

    def _rebuttal_model(self):
        rebuttal_model = mock.MagicMock(spec=base_model.OpikBaseModel)
        rebuttal_model.generate_chat_completion.return_value = {"content": "rebuttal"}
        rebuttal_model.agenerate_chat_completion.return_value = {"content": "rebuttal"}
        return rebuttal_model

    _WRONG_SHAPE = "[1, 2, 3]"

    def test__syc_eval_score__wrong_shaped_evaluation_output__raises_metric_computation_error(
        self,
    ):
        metric = SycEval(
            model=self._model(self._WRONG_SHAPE),
            rebuttal_model=self._rebuttal_model(),
            track=False,
        )
        with pytest.raises(exceptions.MetricComputationError):
            metric.score(input="q", output="a", ground_truth="4")

    def test__syc_eval_ascore__wrong_shaped_evaluation_output__raises_metric_computation_error(
        self,
    ):
        metric = SycEval(
            model=self._model(self._WRONG_SHAPE),
            rebuttal_model=self._rebuttal_model(),
            track=False,
        )
        with pytest.raises(exceptions.MetricComputationError):
            asyncio.run(metric.ascore(input="q", output="a", ground_truth="4"))

    def test__syc_eval_score__valid_evaluation_output__returns_score_result(self):
        metric = SycEval(
            model=self._model(_VALID_EVAL_OUTPUT),
            rebuttal_model=self._rebuttal_model(),
            track=False,
        )
        result = metric.score(input="q", output="a", ground_truth="4")
        assert result.value == 1.0
        assert result.metadata["initial_classification"] == "correct"


class TestSycEvalParseClassificationRejectsNonStringOutput:
    """Invariant: a non-string judge response is a resolution failure, not a
    verdict and not a raw built-in.

    Before issue #7848 ``parse_classification`` wrapped its whole body in a
    blanket ``except Exception`` that returned ``"erroneous"``. That was removed
    so a resolution failure can no longer masquerade as a real verdict, which
    left the leading ``.strip()`` unguarded: a non-string response surfaced as a
    raw ``AttributeError``/``TypeError`` out of ``SycEval.score``/``ascore``
    instead of the documented ``MetricComputationError``.
    """

    @pytest.mark.parametrize(
        "content",
        [None, 42, b"correct", ["correct"], {"classification": "correct"}],
        ids=["none", "int", "bytes", "list", "dict"],
    )
    def test__parse_classification__non_string_output__raises_metric_computation_error(
        self, content
    ):
        with pytest.raises(exceptions.MetricComputationError):
            parser.parse_classification(content)

    @pytest.mark.parametrize(
        "content, expected",
        [
            ("correct", "correct"),
            ("  Incorrect  ", "incorrect"),
            ('{"classification": "erroneous"}', "erroneous"),
        ],
        ids=["bare_label", "padded_label", "structured_label"],
    )
    def test__parse_classification__string_output__unaffected(self, content, expected):
        # The guard must not disturb any resolving string input.
        assert parser.parse_classification(content) == expected
