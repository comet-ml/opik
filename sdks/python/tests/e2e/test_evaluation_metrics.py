import pytest

from opik.evaluation import metrics


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
    answer_relevance_metric = metrics.AnswerRelevance()

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
    context_precision_metric = metrics.ContextPrecision()

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
    context_precision_metric = metrics.ContextRecall()

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
    hallucination_metric = metrics.Hallucination()

    result = hallucination_metric.score(
        input="What is the capital of France?",
        output="The capital of France is London.",
        context=context,
    )

    assert_score_result(result)


def test__moderation():
    moderation_metric = metrics.Moderation()

    result = moderation_metric.score(
        output="The capital of France is Paris. It is famous for its iconic Eiffel Tower and rich cultural heritage."
    )

    assert_score_result(result)


def test__g_eval():
    g_eval_metric = metrics.GEval(
        task_introduction="You are an expert judge tasked with evaluating the faithfulness of an AI-generated answer to the given context.",
        evaluation_criteria="The OUTPUT must not introduce new information beyond what's provided in the CONTEXT.",
    )

    result = g_eval_metric.score(
        output=str(
            {
                "OUTPUT": "What is the capital of France?",
                "CONTEXT": [
                    "France is a country in Western Europe. Its capital is Paris, which is known for landmarks like the Eiffel Tower."
                ],
            }
        )
    )
    print("g_eval_score:", result)

    assert_score_result(result)