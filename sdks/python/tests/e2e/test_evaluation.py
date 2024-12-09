import pytest

from opik.evaluation.metrics import AnswerRelevance, ContextPrecision, ContextRecall, Hallucination, Moderation


def assert_score_result(result):
    assert isinstance(result.value, float)
    assert 0.0 <= result.value <= 1.0
    assert isinstance(result.reason, str)
    assert len(result.reason) > 0


@pytest.mark.parametrize(
    argnames="context",
    argvalues=[
        None,
        ["France is a country in Europe."],
    ],
)
def test__answer_relevance(context):
    answer_relevance_metric = AnswerRelevance()

    result = answer_relevance_metric.score(
        input="What's the capital of France?",
        output="The capital of France is Paris.",
        context=context,
    )

    assert_score_result(result)


@pytest.mark.parametrize(
    argnames="context",
    argvalues=[
        None,
        ["France is a country in Europe."],
    ],
)
def test__context_precision(context):
    context_precision_metric = ContextPrecision()

    result = context_precision_metric.score(
        input="What's the capital of France?",
        output="The capital of France is Paris.",
        expected_output="Paris",
        context=context,
    )

    assert_score_result(result)


@pytest.mark.parametrize(
    argnames="context",
    argvalues=[
        None,
        ["France is a country in Europe."],
    ],
)
def test__context_recall(context):
    context_precision_metric = ContextRecall()

    result = context_precision_metric.score(
        input="What's the capital of France?",
        output="The capital of France is Paris.",
        expected_output="Paris",
        context=context,
    )

    assert_score_result(result)


@pytest.mark.parametrize(
    argnames="context",
    argvalues=[
        None,
        ["The capital of France is Paris."],
    ],
)
def test__hallucination(context):
    hallucination_metric = Hallucination()

    result = hallucination_metric.score(
        input="What is the capital of France?",
        output="The capital of France is London.",
        context=context,
    )

    assert_score_result(result)


def test__moderation():
    moderation_metric = Moderation()

    result = moderation_metric.score(
        output="The capital of France is Paris. It is famous for its iconic Eiffel Tower and rich cultural heritage."
    )

    assert_score_result(result)
