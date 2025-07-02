import pytest
from opik.evaluation import metrics
from opik import exceptions
from ...testlib import assert_helpers

pytestmark = pytest.mark.usefixtures("ensure_openai_configured")


def test__answer_relevance__context_provided_happyflow():
    answer_relevance_metric = metrics.AnswerRelevance()

    result = answer_relevance_metric.score(
        input="What's the capital of France?",
        output="The capital of France is Paris.",
        context=["France is a country in Europe."],
    )

    assert_helpers.assert_score_result(result)


def test__answer_relevance__no_context_provided__error_raised():
    answer_relevance_metric = metrics.AnswerRelevance()

    with pytest.raises(exceptions.MetricComputationError):
        _ = answer_relevance_metric.score(
            input="What's the capital of France?",
            output="The capital of France is Paris.",
            context=[],
        )

    with pytest.raises(exceptions.MetricComputationError):
        _ = answer_relevance_metric.score(
            input="What's the capital of France?",
            output="The capital of France is Paris.",
            context=None,
        )

    with pytest.raises(exceptions.MetricComputationError):
        _ = answer_relevance_metric.score(
            input="What's the capital of France?",
            output="The capital of France is Paris.",
        )


def test__answer_relevance__no_context_provided__no_context_mode_is_enabled__happyflow():
    answer_relevance_metric = metrics.AnswerRelevance(require_context=False)

    result = answer_relevance_metric.score(
        input="What's the capital of France?",
        output="The capital of France is Paris.",
    )

    assert_helpers.assert_score_result(result)


def test__no_opik_configured__answer_relevance(
    configure_opik_not_configured,
):
    answer_relevance_metric = metrics.AnswerRelevance()

    result = answer_relevance_metric.score(
        input="What's the capital of France?",
        output="The capital of France is Paris.",
        context=["France is a country in Europe."],
    )

    assert_helpers.assert_score_result(result)


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

    assert_helpers.assert_score_result(result)


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

    assert_helpers.assert_score_result(result)


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

    assert_helpers.assert_score_result(result)


def test__moderation():
    moderation_metric = metrics.Moderation()

    result = moderation_metric.score(
        output="The capital of France is Paris. It is famous for its iconic Eiffel Tower and rich cultural heritage."
    )

    assert_helpers.assert_score_result(result)


def test__g_eval():
    g_eval_metric = metrics.GEval(
        task_introduction="You are an expert judge tasked with evaluating the faithfulness of an AI-generated answer to the given context.",
        evaluation_criteria="In provided text the OUTPUT must not introduce new information beyond what's provided in the CONTEXT.",
    )

    result = g_eval_metric.score(
        output="""
                OUTPUT: What is the capital of France?
                CONTEXT: France is a country in Western Europe, Its capital is Paris, which is known for landmarks like the Eiffel Tower.
               """
    )

    assert_helpers.assert_score_result(result)


def test__trajectory_accuracy():
    trajectory_accuracy_metric = metrics.TrajectoryAccuracy()

    result = trajectory_accuracy_metric.score(
        goal="Find the weather in Paris",
        trajectory=[
            {
                "thought": "I need to search for weather information in Paris",
                "action": "search_weather(location='Paris')",
                "observation": "Found weather data for Paris: 22°C, sunny"
            },
            {
                "thought": "I have the weather data, now I should summarize it",
                "action": "summarize_result()",
                "observation": "Summary created: The weather in Paris is 22°C and sunny"
            }
        ],
        final_result="The weather in Paris is 22°C and sunny"
    )

    assert_helpers.assert_score_result(result)


@pytest.mark.asyncio
async def test__trajectory_accuracy__async():
    trajectory_accuracy_metric = metrics.TrajectoryAccuracy()

    result = await trajectory_accuracy_metric.ascore(
        goal="Calculate the sum of 15 and 27",
        trajectory=[
            {
                "thought": "I need to add 15 and 27 together",
                "action": "calculate(15 + 27)",
                "observation": "Result: 42"
            }
        ],
        final_result="The sum of 15 and 27 is 42"
    )

    assert_helpers.assert_score_result(result)


def test__trajectory_accuracy__poor_quality():
    """Test trajectory accuracy with a poorly executed trajectory."""
    trajectory_accuracy_metric = metrics.TrajectoryAccuracy()

    result = trajectory_accuracy_metric.score(
        goal="Find the capital of France",
        trajectory=[
            {
                "thought": "I need to find France's capital",
                "action": "search('weather in France')",  # Wrong action
                "observation": "Found weather information for various French cities"
            },
            {
                "thought": "This doesn't help, let me try something else",
                "action": "search('French cuisine')",  # Still wrong
                "observation": "Found information about French food"
            }
        ],
        final_result="Paris is the capital of France"  # Result doesn't match trajectory
    )

    assert_helpers.assert_score_result(result)
    # The score should be low due to inappropriate actions
    assert result.value < 0.6  # Should get a low score for poor trajectory
