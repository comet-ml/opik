import pytest
from opik.evaluation import metrics
from opik import exceptions
from ...testlib import assert_helpers
import langchain_openai
from opik.evaluation.models.langchain import langchain_chat_model
from ragas import metrics as ragas_metrics
from ragas import llms as ragas_llms

pytestmark = pytest.mark.usefixtures("ensure_openai_configured")


model_parametrizer = pytest.mark.parametrize(
    argnames="model",
    argvalues=[
        "gpt-4o",
        langchain_chat_model.LangchainChatModel(
            chat_model=langchain_openai.ChatOpenAI(
                model_name="gpt-4o",
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
                OUTPUT: What is the capital of France?
                CONTEXT: France is a country in Western Europe. Its capital is Paris, which is known for landmarks like the Eiffel Tower.
               """
    )

    assert_helpers.assert_score_result(result)


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
        langchain_openai.ChatOpenAI(model_name="gpt-4o"),
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
