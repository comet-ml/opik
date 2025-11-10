import functools
import inspect
import logging
from typing import List, Optional, Callable, Any, Dict

import opik.exceptions as exceptions
import opik.logging_messages as logging_messages
import opik.opik_context as opik_context
import opik
from opik.api_objects import opik_client, trace
from opik.api_objects.dataset import dataset, dataset_item
from opik.api_objects.experiment import experiment
from opik.evaluation import (
    rest_operations,
    test_case,
    test_result,
    samplers,
)
from opik.evaluation.types import LLMTask, ScoringKeyMappingType

from . import evaluation_tasks_executor, exception_analyzer, helpers
from .types import EvaluationTask
from ..metrics import arguments_validator, arguments_helpers, base_metric, score_result
from ..scorers import scorer_wrapper_metric
from ...message_processing import message_processors_chain
from ...message_processing.emulation import models


LOGGER = logging.getLogger(__name__)

EVALUATION_TASK_NAME = "evaluation_task"
EVALUATION_SPAN_PARAMETER_NAME = "task_span"


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
        self._scoring_metrics: List[base_metric.BaseMetric] = []
        self._task_span_scoring_metrics: List[base_metric.BaseMetric] = []
        self._scoring_key_mapping = scoring_key_mapping

        # Analyze metrics
        self._analyze_metrics(scoring_metrics)

        if len(self._task_span_scoring_metrics) > 0:
            LOGGER.info(
                "Detected %d LLM task span scoring metrics — enabling handling of the LLM task evaluation span.",
                len(self._task_span_scoring_metrics),
            )

    def _analyze_metrics(self, scoring_metrics: List[base_metric.BaseMetric]) -> None:
        for metric in scoring_metrics:
            if _has_evaluation_span_parameter(metric.score):
                self._task_span_scoring_metrics.append(metric)
            else:
                self._scoring_metrics.append(metric)

    @opik.track(name="metrics_calculation")  # type: ignore[attr-defined,has-type]
    def _evaluate_test_case(
        self,
        test_case_: test_case.TestCase,
        trial_id: int = 0,
    ) -> test_result.TestResult:
        score_results = _scores_by_metrics(
            scoring_metrics=self._scoring_metrics,
            score_kwargs=test_case_.scoring_inputs,
            scoring_key_mapping=self._scoring_key_mapping,
            test_case_=test_case_,
        )

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

    def _evaluate_llm_task(
        self,
        item: dataset_item.DatasetItem,
        task: LLMTask,
        trial_id: int,
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
            experiment=self._experiment,
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
                dataset_item_content=item_content,
            )
            test_result_ = self._evaluate_test_case(
                test_case_=test_case_,
                trial_id=trial_id,
            )

        return test_result_

    def evaluate_llm_tasks(
        self,
        dataset_: dataset.Dataset,
        task: LLMTask,
        nb_samples: Optional[int],
        dataset_item_ids: Optional[List[str]],
        dataset_sampler: Optional[samplers.BaseDatasetSampler],
        trial_count: int,
    ) -> List[test_result.TestResult]:
        task_span_scoring_enabled = False
        if len(self._task_span_scoring_metrics) > 0:
            message_processors_chain.toggle_local_emulator_message_processor(
                active=True, chain=self._client._message_processor
            )
            task_span_scoring_enabled = True

        dataset_items = dataset_.__internal_api__get_items_as_dataclasses__(
            nb_samples=nb_samples,
            dataset_item_ids=dataset_item_ids,
        )

        if dataset_sampler is not None:
            dataset_items = dataset_sampler.sample(dataset_items)

        test_results: List[test_result.TestResult] = []

        for trial_id in range(trial_count):
            evaluation_tasks: List[EvaluationTask[test_result.TestResult]] = [
                functools.partial(
                    self._evaluate_llm_task,
                    item=item,
                    task=task,
                    trial_id=trial_id,
                )
                for item in dataset_items
            ]

            test_results += evaluation_tasks_executor.execute(
                evaluation_tasks,
                self._workers,
                self._verbose,
                desc=f"Evaluation trial {trial_id}"
                if trial_count > 1
                else "Evaluation",
            )

        if task_span_scoring_enabled:
            # flush Opik client to make sure all spans are collected
            self._client.flush()

            self._evaluate_llm_tasks_spans(test_results)

            LOGGER.info(
                "Task evaluation span handling is disabled — the evaluation has been completed."
            )
            message_processors_chain.toggle_local_emulator_message_processor(
                active=False, chain=self._client._message_processor
            )

        return test_results

    def _evaluate_llm_tasks_spans(
        self, test_results: List[test_result.TestResult]
    ) -> None:
        local = message_processors_chain.get_local_emulator_message_processor(
            chain=self._client._message_processor
        )
        if local is None:
            LOGGER.warning("Local emulator message processor not found in the chain.")
            return

        # get trace trees from a local emulator
        trace_trees = local.trace_trees
        if len(trace_trees) == 0:
            LOGGER.warning("No trace trees found in the local emulator.")
            return

        # create span evaluation tasks from LLM tasks evaluation results and evaluate them in parallel
        span_evaluation_tasks: List[EvaluationTask[test_result.TestResult]] = [
            functools.partial(
                self._evaluate_llm_task_result_span,
                evaluation_task_result=test_result_,
                trace_trees=trace_trees,
            )
            for test_result_ in test_results
        ]

        evaluation_tasks_executor.execute(
            span_evaluation_tasks,
            self._workers,
            self._verbose,
            desc="LLM task spans evaluation",
        )

    def _evaluate_llm_task_result_span(
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
            score_results = self._score_llm_task_result_span(
                trace_id=trace_id,
                task_span=evaluation_span,
                test_case_=evaluation_task_result.test_case,
            )
            # append scores to the input test result
            evaluation_task_result.score_results += score_results
            return evaluation_task_result

    @opik.track(  # type: ignore[attr-defined,has-type]
        name="task_span_metrics_calculation",
        ignore_arguments=["test_case_"],
    )
    def _score_llm_task_result_span(
        self,
        trace_id: str,
        task_span: models.SpanModel,
        test_case_: test_case.TestCase,
    ) -> List[score_result.ScoreResult]:
        score_kwargs = {
            **test_case_.scoring_inputs,
            EVALUATION_SPAN_PARAMETER_NAME: task_span,
        }

        score_results = _scores_by_metrics(
            scoring_metrics=self._task_span_scoring_metrics,
            score_kwargs=score_kwargs,
            scoring_key_mapping=self._scoring_key_mapping,
            test_case_=test_case_,
        )

        # log feedback scores
        rest_operations.log_test_result_feedback_scores(
            client=self._client,
            score_results=score_results,
            trace_id=trace_id,
            project_name=self._project_name,
        )
        return score_results

    def evaluate_test_cases(
        self,
        test_cases: List[test_case.TestCase],
    ) -> List[test_result.TestResult]:
        evaluation_tasks: List[EvaluationTask[test_result.TestResult]] = [
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


def _scores_by_metrics(
    scoring_metrics: List[base_metric.BaseMetric],
    score_kwargs: Dict[str, Any],
    scoring_key_mapping: Optional[ScoringKeyMappingType],
    test_case_: test_case.TestCase,
) -> List[score_result.ScoreResult]:
    score_results: List[score_result.ScoreResult] = []
    for metric in scoring_metrics:
        try:
            LOGGER.debug("Metric %s score started", metric.name)

            if isinstance(metric, scorer_wrapper_metric.ScorerWrapperMetric):
                # use original dataset item content without any mappings applied
                if (
                    task_span := score_kwargs.get(EVALUATION_SPAN_PARAMETER_NAME)
                ) is not None:
                    result = metric.score(
                        dataset_item=test_case_.dataset_item_content,
                        task_outputs=test_case_.task_output,
                        task_span=task_span,
                    )
                else:
                    result = metric.score(
                        dataset_item=test_case_.dataset_item_content,
                        task_outputs=test_case_.task_output,
                    )
            else:
                arguments_validator.validate_score_arguments(
                    metric=metric,
                    kwargs=score_kwargs,
                    scoring_key_mapping=scoring_key_mapping,
                )
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

    return score_results


def _has_evaluation_span_parameter(func: Callable) -> bool:
    try:
        sig = inspect.signature(func)
        has_param = EVALUATION_SPAN_PARAMETER_NAME in sig.parameters
    except (ValueError, TypeError):
        # If we can't inspect the signature, assume no parameter
        has_param = False

    return has_param
