from typing import List, Dict, Any, Optional
from opik_optimizer import predictor, prompt_parameter
from opik.evaluation import metrics
import opik
from opik.evaluation.metrics import score_result


def evaluate_predictor(
    dataset: opik.Dataset,
    validation_items_ids: Optional[List[str]],
    predictor_: predictor.OpenAIPredictor,
    predictor_parameter: prompt_parameter.PromptParameter,
    metric: metrics.BaseMetric,
    num_threads: int,
    metric_inputs_from_dataset_columns_mapping: Dict[str, str],
    metric_inputs_from_predictor_output_mapping: Dict[str, str],
    project_name: Optional[str],
    num_test: Optional[int] = None,
) -> float:
    score_results: List[score_result.ScoreResult] = []

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        output = predictor_.predict(
            prompt_variables=item,
            prompt_parameter=predictor_parameter,
        )
        return {"evaluated_output": output}

    scoring_key_mapping = {
        **metric_inputs_from_dataset_columns_mapping,
        **metric_inputs_from_predictor_output_mapping,
    }

    evaluation_result = opik.evaluate(
        dataset=dataset,
        task=task,
        project_name=project_name,
        scoring_key_mapping=scoring_key_mapping,
        dataset_item_ids=validation_items_ids,
        scoring_metrics=[metric],
        task_threads=num_threads,
        nb_samples=num_test,
    )

    # We may allow score aggregation customization.
    score_results: List[score_result.ScoreResult] = [
        test_result.score_results[0] for test_result in evaluation_result.test_results
    ]

    return sum([score_result_.value for score_result_ in score_results]) / len(
        score_results
    )
