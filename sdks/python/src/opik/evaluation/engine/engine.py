import functools
import logging
from typing import List, Optional

from opik import exceptions, logging_messages, opik_context, track
from opik.api_objects import opik_client, trace
from opik.api_objects.dataset import dataset, dataset_item
from opik.api_objects.experiment import experiment
from opik.evaluation import (
    rest_operations,
    test_case,
    test_result,
)
from opik.evaluation.types import LLMTask, ScoringKeyMappingType

from . import evaluation_tasks_executor, exception_analyzer, helpers
from .types import EvaluationTask
from ..metrics import arguments_validator, arguments_helpers, base_metric, score_result

LOGGER = logging.getLogger(__name__)


class EvaluationEngine:
    def __init__(
        self,
        client: opik_client.Opik,
        project_name: Optional[str],
        experiment_: experiment.Experiment,
        scoring_metrics: List[base_metric.BaseMetric],
        workers: int,
        verbose: int,
        scoring_key_mapping: Optional[ScoringKeyMappingType],
    ) -> None:
        self._client = client
        self._project_name = project_name
        self._experiment = experiment_
        self._workers = workers
        self._verbose = verbose
        self._scoring_metrics = scoring_metrics
        self._scoring_key_mapping = scoring_key_mapping

    @track(name="metrics_calculation")
    def _evaluate_test_case(
        self,
        test_case_: test_case.TestCase,
    ) -> test_result.TestResult:
        score_results: List[score_result.ScoreResult] = []

        for metric in self._scoring_metrics:
            try:
                score_kwargs = test_case_.scoring_inputs
                arguments_validator.validate_score_arguments(
                    metric=metric,
                    kwargs=score_kwargs,
                    scoring_key_mapping=self._scoring_key_mapping,
                )
                LOGGER.debug("Metric %s score started", metric.name)
                result = metric.score(**score_kwargs)
                LOGGER.debug("Metric %s score ended", metric.name)

                if isinstance(result, list):
                    score_results += result
                else:
                    score_results.append(result)
            except exceptions.ScoreMethodMissingArguments:
                raise
            except Exception as exception:
                # This can be problematic if the metric returns a list of strings as we will not know the name of the metrics that have failed
                LOGGER.error(
                    "Failed to compute metric %s. Score result will be marked as failed.",
                    metric.name,
                    exc_info=True,
                )

                if exception_analyzer.is_llm_provider_rate_limit_error(exception):
                    LOGGER.error(
                        logging_messages.LLM_PROVIDER_RATE_LIMIT_ERROR_DETECTED_IN_EVALUATE_FUNCTION
                    )

                score_results.append(
                    score_result.ScoreResult(
                        name=metric.name,
                        value=0.0,
                        reason=str(exception),
                        scoring_failed=True,
                    )
                )

        test_result_ = test_result.TestResult(
            test_case=test_case_, score_results=score_results
        )
        rest_operations.log_test_result_scores(
            client=self._client,
            test_result=test_result_,
            project_name=self._project_name,
        )
        return test_result_

    def _evaluate_llm_task(
        self,
        item: dataset_item.DatasetItem,
        task: LLMTask,
    ) -> test_result.TestResult:
        if not hasattr(task, "opik_tracked"):
            name = task.__name__ if hasattr(task, "__name__") else "llm_task"
            task = track(name=name)(task)

        trace_data = trace.TraceData(
            input=item.get_content(),
            name="evaluation_task",
            created_by="evaluation",
            project_name=self._project_name,
        )

        with helpers.evaluate_llm_task_context(
            experiment=self._experiment,
            dataset_item_id=item.id,
            trace_data=trace_data,
            client=self._client,
        ):
            item_content = item.get_content()

            LOGGER.debug("Task started, input: %s", item_content)
            try:
                task_output_ = task(item_content)
            except Exception as exception:
                if exception_analyzer.is_llm_provider_rate_limit_error(exception):
                    LOGGER.error(
                        logging_messages.LLM_PROVIDER_RATE_LIMIT_ERROR_DETECTED_IN_EVALUATE_FUNCTION
                    )

                raise
            LOGGER.debug("Task finished, output: %s", task_output_)

            opik_context.update_current_trace(output=task_output_)

            scoring_inputs = arguments_helpers.create_scoring_inputs(
                dataset_item=item_content,
                task_output=task_output_,
                scoring_key_mapping=self._scoring_key_mapping,
            )

            test_case_ = test_case.TestCase(
                trace_id=trace_data.id,
                dataset_item_id=item.id,
                scoring_inputs=scoring_inputs,
                task_output=task_output_,
            )
            test_result_ = self._evaluate_test_case(
                test_case_=test_case_,
            )

        return test_result_

    def evaluate_llm_tasks(
        self,
        dataset_: dataset.Dataset,
        task: LLMTask,
        nb_samples: Optional[int],
        dataset_item_ids: Optional[List[str]],
    ) -> List[test_result.TestResult]:
        dataset_items = dataset_.__internal_api__get_items_as_dataclasses__(
            nb_samples=nb_samples,
            dataset_item_ids=dataset_item_ids,
        )

        evaluation_tasks: List[EvaluationTask] = [
            functools.partial(
                self._evaluate_llm_task,
                item=item,
                task=task,
            )
            for item in dataset_items
        ]

        test_results = evaluation_tasks_executor.execute(
            evaluation_tasks, self._workers, self._verbose
        )

        return test_results

    def evaluate_test_cases(
        self,
        test_cases: List[test_case.TestCase],
    ) -> List[test_result.TestResult]:
        evaluation_tasks: List[EvaluationTask] = [
            functools.partial(
                self._evaluate_test_case,
                test_case_=test_case_,
            )
            for test_case_ in test_cases
        ]

        test_results = evaluation_tasks_executor.execute(
            evaluation_tasks, self._workers, self._verbose
        )

        return test_results
