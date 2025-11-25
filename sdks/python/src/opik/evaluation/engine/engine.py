import functools
import logging
from typing import List, Optional, Any, Dict

import opik.logging_messages as logging_messages
import opik.opik_context as opik_context
import opik
from opik.api_objects import opik_client, trace, local_recording
from opik.api_objects.dataset import dataset, dataset_item
from opik.api_objects.experiment import experiment
from opik.evaluation import (
    rest_operations,
    test_case,
    test_result,
    samplers,
)
from opik.evaluation.types import LLMTask, ScoringKeyMappingType

from . import evaluation_tasks_executor, exception_analyzer, helpers, metrics_evaluator
from .types import EvaluationTask
from ..metrics import base_metric, score_result
from ...message_processing.emulation import models


LOGGER = logging.getLogger(__name__)

EVALUATION_TASK_NAME = "evaluation_task"


class EvaluationEngine:
    def __init__(
        self,
        client: opik_client.Opik,
        project_name: Optional[str],
        scoring_metrics: List[base_metric.BaseMetric],
        workers: int,
        verbose: int,
        scoring_key_mapping: Optional[ScoringKeyMappingType],
    ) -> None:
        self._client = client
        self._project_name = project_name
        self._workers = workers
        self._verbose = verbose

        # Delegate metric analysis to MetricsEvaluator
        self._metrics_evaluator = metrics_evaluator.MetricsEvaluator(
            scoring_metrics=scoring_metrics,
            scoring_key_mapping=scoring_key_mapping,
        )

    @opik.track(name="metrics_calculation")  # type: ignore[attr-defined,has-type]
    def _compute_test_result_for_test_case(
        self,
        test_case_: test_case.TestCase,
        trial_id: int = 0,
    ) -> test_result.TestResult:
        score_results, mapped_scoring_inputs = (
            self._metrics_evaluator.compute_regular_scores(
                dataset_item_content=test_case_.dataset_item_content,
                task_output=test_case_.task_output,
            )
        )
        test_case_.mapped_scoring_inputs = mapped_scoring_inputs

        test_result_ = test_result.TestResult(
            test_case=test_case_,
            score_results=score_results,
            trial_id=trial_id,
        )
        rest_operations.log_test_result_feedback_scores(
            client=self._client,
            score_results=score_results,
            trace_id=test_case_.trace_id,
            project_name=self._project_name,
        )
        return test_result_

    @opik.track(  # type: ignore[attr-defined,has-type]
        name="task_span_metrics_calculation",
        ignore_arguments=["test_case_"],
    )
    def _compute_scores_for_test_case_with_task_span(
        self,
        trace_id: str,
        task_span: models.SpanModel,
        test_case_: test_case.TestCase,
    ) -> List[score_result.ScoreResult]:
        score_results, mapped_scoring_inputs = (
            self._metrics_evaluator.compute_task_span_scores(
                dataset_item_content=test_case_.dataset_item_content,
                task_output=test_case_.task_output,
                task_span=task_span,
            )
        )
        test_case_.mapped_scoring_inputs = mapped_scoring_inputs

        # log feedback scores
        rest_operations.log_test_result_feedback_scores(
            client=self._client,
            score_results=score_results,
            trace_id=trace_id,
            project_name=self._project_name,
        )
        return score_results

    def _compute_test_result_for_llm_task(
        self,
        item: dataset_item.DatasetItem,
        task: LLMTask,
        trial_id: int,
        experiment_: Optional[experiment.Experiment],
    ) -> test_result.TestResult:
        if not hasattr(task, "opik_tracked"):
            name = task.__name__ if hasattr(task, "__name__") else "llm_task"
            task = opik.track(name=name)(task)  # type: ignore[attr-defined,has-type]

        item_content = item.get_content(include_id=True)
        trace_data = trace.TraceData(
            input=item_content,
            name=EVALUATION_TASK_NAME,
            created_by="evaluation",
            project_name=self._project_name,
        )

        with helpers.evaluate_llm_task_context(
            experiment=experiment_,
            dataset_item_id=item.id,
            trace_data=trace_data,
            client=self._client,
        ):
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

            test_case_ = test_case.TestCase(
                trace_id=trace_data.id,
                dataset_item_id=item.id,
                task_output=task_output_,
                dataset_item_content=item_content,
            )
            test_result_ = self._compute_test_result_for_test_case(
                test_case_=test_case_,
                trial_id=trial_id,
            )

        return test_result_

    def _compute_test_results_for_llm_task(
        self,
        dataset_items: List[dataset_item.DatasetItem],
        task: LLMTask,
        experiment_: Optional[experiment.Experiment],
        trial_count: int,
        description: str,
    ) -> List[test_result.TestResult]:
        test_results: List[test_result.TestResult] = []

        for trial_id in range(trial_count):
            evaluation_tasks: List[EvaluationTask[test_result.TestResult]] = [
                functools.partial(
                    self._compute_test_result_for_llm_task,
                    item=item,
                    task=task,
                    trial_id=trial_id,
                    experiment_=experiment_,
                )
                for item in dataset_items
            ]

            test_results += evaluation_tasks_executor.execute(
                evaluation_tasks=evaluation_tasks,
                workers=self._workers,
                verbose=self._verbose,
                desc=f"{description} trial {trial_id}"
                if trial_count > 1
                else description,
            )

        return test_results

    def _update_test_result_with_task_span_metrics(
        self,
        evaluation_task_result: test_result.TestResult,
        trace_trees: List[models.TraceModel],
    ) -> test_result.TestResult:
        # find related trace
        trace_id = evaluation_task_result.test_case.trace_id
        task_trace = None
        for trace_ in trace_trees:
            if trace_.id == trace_id:
                task_trace = trace_
                break

        if task_trace is None:
            raise ValueError(
                f"No trace found for test result: {evaluation_task_result}"
            )

        # find evaluation span
        if len(task_trace.spans) == 0:
            raise ValueError(
                f"Task trace contains no spans. Task span metrics require at least one span to be present in the execution trace. Test result: {evaluation_task_result}"
            )
        # the first span is the evaluation span
        evaluation_span = task_trace.spans[0]

        with helpers.evaluate_llm_task_result_spans_context(
            trace_data=trace.TraceData(
                id=trace_id,
                name=task_trace.name,
                start_time=task_trace.start_time,
                metadata=task_trace.metadata,
                input=task_trace.input,
                output=task_trace.output,
                tags=task_trace.tags,
                project_name=self._project_name,
                created_by="evaluation",
                error_info=task_trace.error_info,
                thread_id=task_trace.thread_id,
            ),
            client=self._client,
        ):
            score_results = self._compute_scores_for_test_case_with_task_span(
                trace_id=trace_id,
                task_span=evaluation_span,
                test_case_=evaluation_task_result.test_case,
            )
            # append scores to the input test result
            evaluation_task_result.score_results += score_results
            return evaluation_task_result

    def _update_test_results_with_task_span_metrics(
        self,
        test_results: List[test_result.TestResult],
        recording: local_recording._LocalRecordingHandle,
    ) -> None:
        """Evaluate task spans from a local recording."""
        # Get trace trees from the recording (this flushes automatically)
        trace_trees = recording.trace_trees
        if len(trace_trees) == 0:
            LOGGER.warning("No trace trees found in the local recording.")
            return

        # Create span evaluation tasks from LLM tasks evaluation results and evaluate them in parallel
        span_evaluation_tasks: List[EvaluationTask[test_result.TestResult]] = [
            functools.partial(
                self._update_test_result_with_task_span_metrics,
                evaluation_task_result=test_result_,
                trace_trees=trace_trees,
            )
            for test_result_ in test_results
        ]

        evaluation_tasks_executor.execute(
            evaluation_tasks=span_evaluation_tasks,
            workers=self._workers,
            verbose=self._verbose,
            desc="LLM task spans evaluation",
        )

        LOGGER.debug(
            "Task evaluation span handling is disabled — the evaluation has been completed."
        )

    def evaluate_llm_task_on_dataset(
        self,
        dataset_: dataset.Dataset,
        task: LLMTask,
        nb_samples: Optional[int],
        dataset_item_ids: Optional[List[str]],
        dataset_sampler: Optional[samplers.BaseDatasetSampler],
        trial_count: int,
        experiment_: Optional[experiment.Experiment],
    ) -> List[test_result.TestResult]:
        dataset_items = dataset_.__internal_api__get_items_as_dataclasses__(
            nb_samples=nb_samples,
            dataset_item_ids=dataset_item_ids,
        )

        if dataset_sampler is not None:
            dataset_items = dataset_sampler.sample(dataset_items)

        if not self._metrics_evaluator.has_task_span_metrics:
            return self._compute_test_results_for_llm_task(
                dataset_items=dataset_items,
                task=task,
                experiment_=experiment_,
                trial_count=trial_count,
                description="Evaluation",
            )

        LOGGER.debug(
            "Detected %d LLM task span scoring metrics — enabling handling of the LLM task evaluation span.",
            len(self._metrics_evaluator.task_span_metrics),
        )

        with local_recording.record_traces_locally(client=self._client) as recording:
            test_results = self._compute_test_results_for_llm_task(
                dataset_items=dataset_items,
                task=task,
                experiment_=experiment_,
                trial_count=trial_count,
                description="Evaluation",
            )
            self._update_test_results_with_task_span_metrics(
                test_results=test_results,
                recording=recording,
            )

        return test_results

    def evaluate_llm_task_on_dict_items(
        self,
        items: List[Dict[str, Any]],
        task: LLMTask,
    ) -> List[test_result.TestResult]:
        """
        Evaluate an LLM task on a list of dict items.

        This method creates traces for each evaluation but doesn't require a Dataset object
        or experiment. It's useful for optimization scenarios where you have items in memory
        and want to evaluate them with a task function.

        Args:
            items: List of dataset item contents (dictionaries).
            task: A callable that takes a dataset item dict and returns a dict with outputs.

        Returns:
            List of TestResult objects containing scores for each item.
        """
        # Convert raw items to DatasetItem objects for compatibility
        dataset_items = [
            dataset_item.DatasetItem(
                id=f"temp_item_{idx}",
                **item,
            )
            for idx, item in enumerate(items)
        ]

        if not self._metrics_evaluator.has_task_span_metrics:
            return self._compute_test_results_for_llm_task(
                dataset_items=dataset_items,
                task=task,
                experiment_=None,
                trial_count=1,
                description="Items evaluation",
            )

        LOGGER.debug(
            "Detected %d LLM task span scoring metrics — enabling handling of the LLM task evaluation span.",
            len(self._metrics_evaluator.task_span_metrics),
        )

        with local_recording.record_traces_locally(client=self._client) as recording:
            test_results = self._compute_test_results_for_llm_task(
                dataset_items=dataset_items,
                task=task,
                experiment_=None,
                trial_count=1,
                description="Items evaluation",
            )
            self._update_test_results_with_task_span_metrics(
                test_results=test_results,
                recording=recording,
            )

        return test_results

    def evaluate_test_cases(
        self,
        test_cases: List[test_case.TestCase],
    ) -> List[test_result.TestResult]:
        evaluation_tasks: List[EvaluationTask[test_result.TestResult]] = [
            functools.partial(
                self._compute_test_result_for_test_case,
                test_case_=test_case_,
            )
            for test_case_ in test_cases
        ]

        test_results = evaluation_tasks_executor.execute(
            evaluation_tasks=evaluation_tasks,
            workers=self._workers,
            verbose=self._verbose,
        )

        return test_results
