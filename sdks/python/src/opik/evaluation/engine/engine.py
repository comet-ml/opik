import functools
import logging
from collections import defaultdict
from concurrent import futures
from typing import List, Optional, Any, Dict, Iterator, Union, Callable

import opik
import opik.logging_messages as logging_messages
import opik.opik_context as opik_context
from opik.api_objects import opik_client, trace, local_recording
from opik.api_objects.dataset import dataset, dataset_item
from opik.api_objects.experiment import experiment
from opik.api_objects.evaluation_suite.types import ExecutionPolicy
from opik.evaluation import rest_operations, test_case, test_result, samplers
from opik.evaluation.types import LLMTask, ScoringKeyMappingType
from opik.message_processing.emulation import models

from . import evaluation_tasks_executor, exception_analyzer, helpers, metrics_evaluator
from .types import EvaluationTask
from ..metrics import base_metric, score_result


LOGGER = logging.getLogger(__name__)

EVALUATION_TASK_NAME = "evaluation_task"

EVALUATION_STREAM_DATASET_BATCH_SIZE = 200


def get_item_execution_policy(
    item: dataset_item.DatasetItem,
    default_policy: ExecutionPolicy,
) -> ExecutionPolicy:
    """
    Get execution policy for a dataset item.

    If the item has its own execution policy, merge it with the default.
    Item-level values override default values.

    Args:
        item: The dataset item.
        default_policy: Default execution policy from suite level.

    Returns:
        Merged execution policy for this item.
    """
    if item.execution_policy is None:
        return default_policy

    return {
        "runs_per_item": (
            item.execution_policy.runs_per_item
            if item.execution_policy.runs_per_item is not None
            else default_policy.get("runs_per_item", 1)
        ),
        "pass_threshold": (
            item.execution_policy.pass_threshold
            if item.execution_policy.pass_threshold is not None
            else default_policy.get("pass_threshold", 1)
        ),
    }


def _calculate_total_items(
    dataset_: Union[dataset.Dataset, dataset.DatasetVersion],
    nb_samples: Optional[int],
    dataset_item_ids: Optional[List[str]],
) -> Optional[int]:
    """
    Calculate the total number of items that will be evaluated.

    Returns None if the total cannot be determined (e.g., when using a sampler).
    """
    if dataset_item_ids is not None:
        return len(dataset_item_ids)

    # If nb_samples is specified and smaller than dataset size, use it
    if nb_samples is not None:
        if dataset_.dataset_items_count is not None:
            return min(nb_samples, dataset_.dataset_items_count)
        return nb_samples

    return dataset_.dataset_items_count


class EvaluationEngine:
    """
    Stateless evaluation executor.

    Only stores configuration (client, workers, verbosity).
    All flow-specific data (metrics, key mappings) is passed as method parameters.
    """

    def __init__(
        self,
        client: opik_client.Opik,
        project_name: Optional[str],
        workers: int,
        verbose: int,
    ) -> None:
        self._client = client
        self._project_name = project_name
        self._workers = workers
        self._verbose = verbose

    # --- Private: metrics & scoring ---

    @opik.track(  # type: ignore[attr-defined,has-type]
        name="metrics_calculation",
        ignore_arguments=[
            "regular_metrics",
            "scoring_key_mapping",
            "evaluator_model",
        ],
    )
    def _compute_test_result_for_test_case(
        self,
        test_case_: test_case.TestCase,
        regular_metrics: List[base_metric.BaseMetric],
        scoring_key_mapping: Optional[ScoringKeyMappingType],
        evaluator_model: Optional[str],
        trial_id: int = 0,
    ) -> test_result.TestResult:
        item_evaluator = metrics_evaluator.build_metrics_evaluator(
            item=test_case_.dataset_item,
            regular_metrics=regular_metrics,
            scoring_key_mapping=scoring_key_mapping,
            evaluator_model=evaluator_model,
        )
        score_results, mapped_scoring_inputs = item_evaluator.compute_regular_scores(
            dataset_item_content=test_case_.dataset_item_content,
            task_output=test_case_.task_output,
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
        ignore_arguments=["test_case_", "task_span_evaluator"],
    )
    def _compute_scores_for_test_case_with_task_span(
        self,
        trace_id: str,
        task_span: models.SpanModel,
        test_case_: test_case.TestCase,
        task_span_evaluator: metrics_evaluator.MetricsEvaluator,
    ) -> List[score_result.ScoreResult]:
        score_results, mapped_scoring_inputs = (
            task_span_evaluator.compute_task_span_scores(
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

    # --- Private: task execution ---

    def _compute_test_result_for_llm_task(
        self,
        item: dataset_item.DatasetItem,
        task: LLMTask,
        trial_id: int,
        experiment_: Optional[experiment.Experiment],
        regular_metrics: List[base_metric.BaseMetric],
        scoring_key_mapping: Optional[ScoringKeyMappingType],
        evaluator_model: Optional[str],
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
                dataset_item=item,
            )
            test_result_ = self._compute_test_result_for_test_case(
                test_case_=test_case_,
                regular_metrics=regular_metrics,
                scoring_key_mapping=scoring_key_mapping,
                evaluator_model=evaluator_model,
                trial_id=trial_id,
            )

        return test_result_

    # --- Private: parallel execution ---

    def _compute_test_results_with_execution_policy(
        self,
        dataset_items: Iterator[dataset_item.DatasetItem],
        task: LLMTask,
        experiment_: Optional[experiment.Experiment],
        regular_metrics: List[base_metric.BaseMetric],
        scoring_key_mapping: Optional[ScoringKeyMappingType],
        evaluator_model: Optional[str],
        description: str,
        total_items: Optional[int] = None,
        default_execution_policy: Optional[ExecutionPolicy] = None,
    ) -> List[test_result.TestResult]:
        """
        Execute tasks with full parallelism and item-based progress.

        Key features:
        - Stream items as they arrive
        - Submit all runs for each item immediately
        - Track per-item completion
        - Update progress when all runs for an item complete
        - Full parallelism across items AND runs
        """
        effective_default_policy: ExecutionPolicy = default_execution_policy or {
            "runs_per_item": 1,
            "pass_threshold": 1,
        }

        # Track completion per item
        item_runs_total: Dict[str, int] = {}
        item_runs_completed: Dict[str, int] = defaultdict(int)

        all_results: List[test_result.TestResult] = []

        # Map futures to item IDs for tracking
        future_to_item_id: Dict[futures.Future, str] = {}

        # Get tqdm for progress bar
        from opik.environment import get_tqdm_for_current_environment

        _tqdm = get_tqdm_for_current_environment()

        # Progress bar based on ITEMS (not runs)
        progress = _tqdm(
            disable=(self._verbose < 1),
            desc=description,
            total=total_items,
        )

        # Thread pool for parallel execution
        with futures.ThreadPoolExecutor(max_workers=self._workers) as executor:
            # Stream items and submit tasks progressively
            for item in dataset_items:
                item_policy = get_item_execution_policy(item, effective_default_policy)
                item_runs = item_policy.get("runs_per_item", 1)

                # Store resolved execution policy
                item.execution_policy = dataset_item.ExecutionPolicyItem(
                    runs_per_item=item_runs,
                    pass_threshold=item_policy.get("pass_threshold", 1),
                )

                # Track expected runs for this item
                item_runs_total[item.id] = item_runs

                # Submit ALL runs for this item to thread pool
                for run_id in range(item_runs):
                    future = executor.submit(
                        self._compute_test_result_for_llm_task,
                        item=item,
                        task=task,
                        trial_id=run_id,
                        experiment_=experiment_,
                        regular_metrics=regular_metrics,
                        scoring_key_mapping=scoring_key_mapping,
                        evaluator_model=evaluator_model,
                    )
                    future_to_item_id[future] = item.id

            # Process results as they complete
            for future in futures.as_completed(future_to_item_id):
                item_id = future_to_item_id[future]
                result = future.result()
                all_results.append(result)

                # Track item completion
                item_runs_completed[item_id] += 1
                if item_runs_completed[item_id] == item_runs_total[item_id]:
                    # All runs for this item completed!
                    progress.update(1)

        progress.close()
        return all_results

    # --- Private: task-span metrics ---

    def _update_test_result_with_task_span_metrics(
        self,
        evaluation_task_result: test_result.TestResult,
        trace_trees: List[models.TraceModel],
        task_span_evaluator: metrics_evaluator.MetricsEvaluator,
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
                task_span_evaluator=task_span_evaluator,
            )
            # append scores to the input test result
            evaluation_task_result.score_results += score_results
            return evaluation_task_result

    def _update_test_results_with_task_span_metrics(
        self,
        test_results: List[test_result.TestResult],
        recording: local_recording._LocalRecordingHandle,
        task_span_evaluator: metrics_evaluator.MetricsEvaluator,
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
                task_span_evaluator=task_span_evaluator,
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

    # --- Private: shared execution with optional task-span scoring ---

    def _execute_evaluation(
        self,
        dataset_items: Iterator[dataset_item.DatasetItem],
        task: LLMTask,
        experiment_: Optional[experiment.Experiment],
        regular_metrics: List[base_metric.BaseMetric],
        task_span_metrics: List[base_metric.BaseMetric],
        scoring_key_mapping: Optional[ScoringKeyMappingType],
        evaluator_model: Optional[str],
        description: str,
        total_items: Optional[int],
        default_execution_policy: ExecutionPolicy,
    ) -> List[test_result.TestResult]:
        """
        Shared execution logic. Runs the task, scores with regular metrics,
        and optionally scores with task-span metrics.
        """
        compute: Callable[[], List[test_result.TestResult]] = functools.partial(
            self._compute_test_results_with_execution_policy,
            dataset_items=dataset_items,
            task=task,
            experiment_=experiment_,
            regular_metrics=regular_metrics,
            scoring_key_mapping=scoring_key_mapping,
            evaluator_model=evaluator_model,
            description=description,
            total_items=total_items,
            default_execution_policy=default_execution_policy,
        )

        if not task_span_metrics:
            return compute()

        LOGGER.debug(
            "Detected %d LLM task span scoring metrics — enabling handling of the LLM task evaluation span.",
            len(task_span_metrics),
        )

        task_span_evaluator = metrics_evaluator.MetricsEvaluator(
            scoring_metrics=task_span_metrics,
            scoring_key_mapping=scoring_key_mapping,
        )

        with local_recording.record_traces_locally(client=self._client) as recording:
            test_results = compute()
            self._update_test_results_with_task_span_metrics(
                test_results=test_results,
                recording=recording,
                task_span_evaluator=task_span_evaluator,
            )

        return test_results

    # --- Public API ---

    def evaluate_llm_task_on_dataset(
        self,
        dataset_: Union[dataset.Dataset, dataset.DatasetVersion],
        task: LLMTask,
        scoring_metrics: List[base_metric.BaseMetric],
        scoring_key_mapping: Optional[ScoringKeyMappingType],
        trial_count: int,
        nb_samples: Optional[int],
        dataset_item_ids: Optional[List[str]],
        dataset_sampler: Optional[samplers.BaseDatasetSampler],
        experiment_: Optional[experiment.Experiment],
        dataset_filter_string: Optional[str] = None,
    ) -> List[test_result.TestResult]:
        regular_metrics, task_span_metrics = (
            metrics_evaluator.split_into_regular_and_task_span_metrics(scoring_metrics)
        )

        default_execution_policy = ExecutionPolicy(
            runs_per_item=trial_count,
            pass_threshold=trial_count,
        )

        # Resolve dataset items
        use_streaming = dataset_sampler is None
        if use_streaming:
            dataset_items_iter = dataset_.__internal_api__stream_items_as_dataclasses__(
                nb_samples=nb_samples,
                dataset_item_ids=dataset_item_ids,
                batch_size=EVALUATION_STREAM_DATASET_BATCH_SIZE,
                filter_string=dataset_filter_string,
            )
            total_items = _calculate_total_items(
                dataset_=dataset_,
                nb_samples=nb_samples,
                dataset_item_ids=dataset_item_ids,
            )
        else:
            LOGGER.info("Dataset streaming disabled due to sampler")
            dataset_items_list = list(
                dataset_.__internal_api__stream_items_as_dataclasses__(
                    nb_samples=nb_samples,
                    dataset_item_ids=dataset_item_ids,
                    batch_size=EVALUATION_STREAM_DATASET_BATCH_SIZE,
                    filter_string=dataset_filter_string,
                )
            )
            assert dataset_sampler is not None
            dataset_items_list = dataset_sampler.sample(dataset_items_list)
            dataset_items_iter = iter(dataset_items_list)
            total_items = len(dataset_items_list)

        return self._execute_evaluation(
            dataset_items=dataset_items_iter,
            task=task,
            experiment_=experiment_,
            regular_metrics=regular_metrics,
            task_span_metrics=task_span_metrics,
            scoring_key_mapping=scoring_key_mapping,
            evaluator_model=None,
            description="Evaluation",
            total_items=total_items,
            default_execution_policy=default_execution_policy,
        )

    def evaluate_llm_task_on_evaluation_suite(
        self,
        dataset_: dataset.Dataset,
        task: LLMTask,
        evaluator_model: Optional[str],
        experiment_: Optional[experiment.Experiment],
    ) -> List[test_result.TestResult]:
        default_execution_policy = dataset_.get_execution_policy()

        suite_evaluators = dataset_.get_evaluators()
        regular_metrics, task_span_metrics = (
            metrics_evaluator.split_into_regular_and_task_span_metrics(suite_evaluators)
        )

        dataset_items_iter = dataset_.__internal_api__stream_items_as_dataclasses__(
            nb_samples=None,
            dataset_item_ids=None,
            batch_size=EVALUATION_STREAM_DATASET_BATCH_SIZE,
            filter_string=None,
        )
        total_items = dataset_.dataset_items_count

        return self._execute_evaluation(
            dataset_items=dataset_items_iter,
            task=task,
            experiment_=experiment_,
            regular_metrics=regular_metrics,
            task_span_metrics=task_span_metrics,
            scoring_key_mapping=None,
            evaluator_model=evaluator_model,
            description="Evaluation",
            total_items=total_items,
            default_execution_policy=default_execution_policy,
        )

    def evaluate_llm_task_on_dict_items(
        self,
        items: List[Dict[str, Any]],
        task: LLMTask,
        scoring_metrics: List[base_metric.BaseMetric],
        scoring_key_mapping: Optional[ScoringKeyMappingType],
    ) -> List[test_result.TestResult]:
        """
        Evaluate an LLM task on a list of dict items.

        This method creates traces for each evaluation but doesn't require a Dataset object
        or experiment. It's useful for optimization scenarios where you have items in memory
        and want to evaluate them with a task function.

        Args:
            items: List of dataset item contents (dictionaries).
            task: A callable that takes a dataset item dict and returns a dict with outputs.
            scoring_metrics: Metrics to score with.
            scoring_key_mapping: Optional key mapping for scoring.

        Returns:
            List of TestResult objects containing scores for each item.
        """
        regular_metrics, task_span_metrics = (
            metrics_evaluator.split_into_regular_and_task_span_metrics(scoring_metrics)
        )

        # Convert raw items to DatasetItem objects for compatibility
        dataset_items_list = [
            dataset_item.DatasetItem(
                id=f"temp_item_{idx}",
                **item,
            )
            for idx, item in enumerate(items)
        ]

        default_execution_policy = ExecutionPolicy(
            runs_per_item=1,
            pass_threshold=1,
        )

        return self._execute_evaluation(
            dataset_items=iter(dataset_items_list),
            task=task,
            experiment_=None,
            regular_metrics=regular_metrics,
            task_span_metrics=task_span_metrics,
            scoring_key_mapping=scoring_key_mapping,
            evaluator_model=None,
            description="Items evaluation",
            total_items=len(items),
            default_execution_policy=default_execution_policy,
        )

    def evaluate_test_cases(
        self,
        test_cases: List[test_case.TestCase],
        scoring_metrics: List[base_metric.BaseMetric],
        scoring_key_mapping: Optional[ScoringKeyMappingType],
    ) -> List[test_result.TestResult]:
        regular_metrics, _ = metrics_evaluator.split_into_regular_and_task_span_metrics(
            scoring_metrics
        )

        evaluation_tasks: List[EvaluationTask[test_result.TestResult]] = [
            functools.partial(
                self._compute_test_result_for_test_case,
                test_case_=test_case_,
                regular_metrics=regular_metrics,
                scoring_key_mapping=scoring_key_mapping,
                evaluator_model=None,
            )
            for test_case_ in test_cases
        ]

        test_results: List[test_result.TestResult] = evaluation_tasks_executor.execute(
            evaluation_tasks=evaluation_tasks,
            workers=self._workers,
            verbose=self._verbose,
        )

        return test_results
