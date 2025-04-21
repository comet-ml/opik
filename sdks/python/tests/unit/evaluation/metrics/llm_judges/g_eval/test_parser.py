from opik import logging_messages, exceptions
from opik.evaluation.metrics.llm_judges.g_eval import parser
import pytest
from opik.evaluation.metrics.llm_judges.g_eval.metric import GEval


@pytest.mark.parametrize("log_probs_supported", [False, True])
def test_g_eval_score_out_of_range(log_probs_supported: bool):
    metric = GEval(
        task_introduction="You are an expert judge tasked with evaluating the faithfulness of an AI-generated answer to the given context.",
        evaluation_criteria="The OUTPUT must not introduce new information beyond what's provided in the CONTEXT.",
    )
    invalid_model_output = (
        '{"g_eval_score": 1.8, "reason": "Score exceeds valid range."}'  # Score > 1.0
    )

    with pytest.raises(
        exceptions.MetricComputationError,
        match=logging_messages.GEVAL_SCORE_CALC_FAILED,
    ):
        parser.parse_model_output(
            content=invalid_model_output,
            name=metric.name,
            log_probs_supported=log_probs_supported,
        )
