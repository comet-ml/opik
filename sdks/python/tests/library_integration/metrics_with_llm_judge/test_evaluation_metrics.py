import pytest
from opik.evaluation import metrics
from opik import exceptions
from ...testlib import assert_helpers
import langchain_openai
from opik.evaluation.models.langchain import langchain_chat_model
from ragas import metrics as ragas_metrics
from ragas import llms as ragas_llms
from opik.evaluation.metrics.llm_judges.structure_output_compliance.schema import (
    FewShotExampleStructuredOutputCompliance,
)


pytestmark = pytest.mark.usefixtures("ensure_openai_configured")


model_parametrizer = pytest.mark.parametrize(
    argnames="model",
    argvalues=[
        "gpt-4o",
        langchain_chat_model.LangchainChatModel(
            chat_model=langchain_openai.ChatOpenAI(
                model="gpt-4o",
            )
        ),
    ],
)


@model_parametrizer
def test__answer_relevance__context_provided_happyflow(model):
    answer_relevance_metric = metrics.AnswerRelevance(model=model, track=False)

    result = answer_relevance_metric.score(
        input="What's the capital of France?",
        output="The capital of France is Paris.",
        context=["France is a country in Europe."],
    )

    assert_helpers.assert_score_result(result)


@model_parametrizer
def test__answer_relevance__no_context_provided__error_raised(model):
    answer_relevance_metric = metrics.AnswerRelevance(model=model, track=False)

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


@model_parametrizer
def test__answer_relevance__no_context_provided__no_context_mode_is_enabled__happyflow(
    model,
):
    answer_relevance_metric = metrics.AnswerRelevance(
        model=model, require_context=False, track=False
    )

    result = answer_relevance_metric.score(
        input="What's the capital of France?",
        output="The capital of France is Paris.",
    )

    assert_helpers.assert_score_result(result)


@model_parametrizer
def test__no_opik_configured__answer_relevance(configure_opik_not_configured, model):
    answer_relevance_metric = metrics.AnswerRelevance(model=model, track=False)

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
@model_parametrizer
def test__context_precision(context, model):
    context_precision_metric = metrics.ContextPrecision(model=model, track=False)

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
@model_parametrizer
def test__context_recall(context, model):
    context_precision_metric = metrics.ContextRecall(model=model, track=False)

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
@model_parametrizer
def test__hallucination(context, model):
    hallucination_metric = metrics.Hallucination(model=model, track=False)

    result = hallucination_metric.score(
        input="What is the capital of France?",
        output="The capital of France is London.",
        context=context,
    )

    assert_helpers.assert_score_result(result)


@model_parametrizer
def test__moderation(model):
    moderation_metric = metrics.Moderation(model=model, track=False)

    result = moderation_metric.score(
        output="The capital of France is Paris. It is famous for its iconic Eiffel Tower and rich cultural heritage."
    )

    assert_helpers.assert_score_result(result)


@model_parametrizer
def test__g_eval(model):
    g_eval_metric = metrics.GEval(
        model=model,
        track=False,
        task_introduction="You are an expert judge tasked with evaluating the faithfulness of an AI-generated answer to the given context.",
        evaluation_criteria="In provided text the OUTPUT must not introduce new information beyond what's provided in the CONTEXT.",
    )

    result = g_eval_metric.score(
        output="""
                OUTPUT: Paris is the capital of France.
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
                "observation": "Found weather data for Paris: 22°C, sunny",
            },
            {
                "thought": "I have the weather data, now I should summarize it",
                "action": "summarize_result()",
                "observation": "Summary created: The weather in Paris is 22°C and sunny",
            },
        ],
        final_result="The weather in Paris is 22°C and sunny",
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
                "observation": "Result: 42",
            }
        ],
        final_result="The sum of 15 and 27 is 42",
    )

    assert_helpers.assert_score_result(result)


@model_parametrizer
def test__trajectory_accuracy__poor_quality(model):
    """Test trajectory accuracy with a poorly executed trajectory."""
    trajectory_accuracy_metric = metrics.TrajectoryAccuracy(model=model, track=False)

    result = trajectory_accuracy_metric.score(
        goal="Find the capital of France",
        trajectory=[
            {
                "thought": "I need to find France's capital",
                "action": "search('weather in France')",  # Wrong action
                "observation": "Found weather information for various French cities",
            },
            {
                "thought": "This doesn't help, let me try something else",
                "action": "search('French cuisine')",  # Still wrong
                "observation": "Found information about French food",
            },
        ],
        final_result="Paris is the capital of France",  # Result doesn't match trajectory
    )

    assert_helpers.assert_score_result(result)
    # The score should be low due to inappropriate actions
    assert result.value <= 0.61  # Should get a low score for poor trajectory


@model_parametrizer
def test__structured_output_compliance__valid_json(model):
    """Test structured output compliance with valid JSON."""
    structured_output_metric = metrics.StructuredOutputCompliance(
        model=model, track=False
    )

    result = structured_output_metric.score(
        output='{"name": "John", "age": 30, "city": "New York"}'
    )

    assert_helpers.assert_score_result(result)
    assert result.value > 0.5


@model_parametrizer
def test__structured_output_compliance__invalid_json(model):
    """Test structured output compliance with invalid JSON."""
    structured_output_metric = metrics.StructuredOutputCompliance(
        model=model, track=False
    )

    result = structured_output_metric.score(
        output='{"name": "John", "age": 30, "city": New York}'
    )

    assert_helpers.assert_score_result(result)
    # Should get a low score for invalid JSON
    assert result.value < 0.5


@model_parametrizer
def test__structured_output_compliance__with_schema(model):
    """Test structured output compliance with schema validation."""
    structured_output_metric = metrics.StructuredOutputCompliance(
        model=model, track=False
    )

    result = structured_output_metric.score(
        output='{"name": "John", "age": 30}', schema="User(name: str, age: int)"
    )

    assert_helpers.assert_score_result(result)
    assert result.value > 0.5


@model_parametrizer
def test__structured_output_compliance__with_few_shot_examples(model):
    """Test structured output compliance with few-shot examples."""
    few_shot_examples = [
        FewShotExampleStructuredOutputCompliance(
            title="Valid JSON",
            output='{"name": "Alice", "age": 25}',
            schema="User(name: str, age: int)",
            score=True,
            reason="Valid JSON format",
        ),
        FewShotExampleStructuredOutputCompliance(
            title="Invalid JSON",
            output='{"name": "Bob", age: 30}',
            schema="User(name: str, age: int)",
            score=False,
            reason="Missing quotes around age value",
        ),
    ]

    structured_output_metric = metrics.StructuredOutputCompliance(
        model=model, few_shot_examples=few_shot_examples, track=False
    )

    result = structured_output_metric.score(output='{"name": "John", "age": 30}')

    assert_helpers.assert_score_result(result)
    assert result.value > 0.5


@model_parametrizer
def test__structured_output_compliance__with_json_schema(model):
    """Test structured output compliance with JSON schema validation."""
    structured_output_metric = metrics.StructuredOutputCompliance(
        model=model, track=False
    )
    schema = '{"type": "object", "properties": {"name": {"type": "string"}, "age": {"type": "integer"}}, "required": ["name", "age"]}'

    result = structured_output_metric.score(
        output='{"name": "John", "age": 30}', schema=schema
    )

    assert_helpers.assert_score_result(result)
    assert result.value > 0.5


@pytest.mark.asyncio
async def test__structured_output_compliance__async():
    """Test structured output compliance with async model."""

    structured_output_metric = metrics.StructuredOutputCompliance()

    result = await structured_output_metric.ascore(
        output='{"name": "John", "age": 30, "city": "New York"}'
    )

    assert_helpers.assert_score_result(result, include_reason=False)
    assert result.value > 0.5


def test__ragas_exact_match():
    ragas_exact_match_metric = metrics.RagasMetricWrapper(
        ragas_metric=ragas_metrics.ExactMatch(), track=False
    )

    result = ragas_exact_match_metric.score(
        response="Paris",
        reference="Paris",
    )

    assert_helpers.assert_score_result(result, include_reason=False)


def test__ragas_llm_context_precision():
    llm_evaluator = ragas_llms.LangchainLLMWrapper(
        langchain_openai.ChatOpenAI(model="gpt-4o"),
    )

    ragas_context_precision_metric = metrics.RagasMetricWrapper(
        ragas_metric=ragas_metrics.LLMContextPrecisionWithoutReference(
            llm=llm_evaluator
        ),
        track=False,
    )

    result = ragas_context_precision_metric.score(
        input="Where is the Eiffel Tower located?",
        output="The Eiffel Tower is located in Paris.",
        retrieved_contexts=["The Eiffel Tower is located in Paris."],
    )

    assert_helpers.assert_score_result(result, include_reason=False)
