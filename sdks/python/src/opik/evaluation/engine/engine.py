import functools
import logging
from typing import List, Optional, Any, Dict, Iterator, Union

import opik
import opik.logging_messages as logging_messages
import opik.opik_context as opik_context
from opik.api_objects import opik_client, trace, local_recording
from opik.api_objects.dataset import dataset, dataset_item
from opik.api_objects.experiment import experiment
from opik.api_objects.evaluation_suite.types import ExecutionPolicy
from opik.evaluation import rest_operations, test_case, test_result, samplers
from opik.evaluation.types import LLMTask, ScoringKeyMappingType
from opik.evaluation.suite_evaluators import opik_llm_judge_config, llm_judge
from opik.message_processing.emulation import models

from . import evaluation_tasks_executor, exception_analyzer, helpers, metrics_evaluator
from .types import EvaluationTask, EVALUATION_CONFIG_KEY
from ..metrics import base_metric, score_result


LOGGER = logging.getLogger(__name__)

EVALUATION_TASK_NAME = "evaluation_task"

EVALUATION_STREAM_DATASET_BATCH_SIZE = 200  # The limit is 10x smaller than the default streaming limit to improve the UX and not wait too long for the first items to be evaluated


def get_item_execution_policy(
    dataset_item_content: Dict[str, Any],
    default_policy: ExecutionPolicy,
) -> ExecutionPolicy:
    """
    Get execution policy for a dataset item.

    If the item has its own execution policy, merge it with the default.
    Item-level values override default values.

    Args:
        dataset_item_content: The dataset item content dict.
        default_policy: Default execution policy from suite level.

    Returns:
        Merged execution policy for this item.
    """
    eval_config = dataset_item_content.get(EVALUATION_CONFIG_KEY, {})
    item_policy = eval_config.get("execution_policy")
    if item_policy is None:
        return default_policy

    return {
        "runs_per_item": item_policy.get(
            "runs_per_item", default_policy.get("runs_per_item", 1)
        ),
        "pass_threshold": item_policy.get(
            "pass_threshold", default_policy.get("pass_threshold", 1)
        ),
    }


def _extract_item_evaluators(
    dataset_item_content: Dict[str, Any],
) -> List[base_metric.BaseMetric]:
    """
    Extract evaluators from dataset item content.

    If the item has evaluator configs stored under __evaluation_config__.evaluators,
    instantiate LLMJudge evaluators from those configs.

    Args:
        dataset_item_content: The dataset item content dict.

    Returns:
        List of evaluator instances extracted from the item content.
    """
    eval_config = dataset_item_content.get(EVALUATION_CONFIG_KEY, {})
    evaluator_configs = eval_config.get("evaluators")
    if not evaluator_configs:
        return []

    evaluators: List[base_metric.BaseMetric] = []
    for config_dict in evaluator_configs:
        try:
            config = opik_llm_judge_config.LLMJudgeConfig(**config_dict)
            evaluator = llm_judge.LLMJudge.from_config(config)
            evaluators.append(evaluator)
        except Exception as e:
            LOGGER.warning(
                "Failed to instantiate evaluator from config: %s. Error: %s",
                config_dict,
                e,
            )

    return evaluators


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
        self._scoring_key_mapping = scoring_key_mapping

        # Separate suite-level metrics into regular and task-span categories
        self._suite_regular_metrics, self._suite_task_span_metrics = (
            metrics_evaluator.split_into_regular_and_task_span_metrics(scoring_metrics)
        )

        # Keep evaluator for task span metrics computation (needs scoring logic)
        self._task_span_evaluator: Optional[metrics_evaluator.MetricsEvaluator] = None
        if self._suite_task_span_metrics:
            self._task_span_evaluator = metrics_evaluator.MetricsEvaluator(
                scoring_metrics=self._suite_task_span_metrics,
                scoring_key_mapping=scoring_key_mapping,
            )

    @property
    def _has_task_span_metrics(self) -> bool:
        """Check if any task span scoring metrics are configured."""
        return len(self._suite_task_span_metrics) > 0

    def _build_metrics_evaluator(
        self,
        dataset_item_content: Dict[str, Any],
    ) -> metrics_evaluator.MetricsEvaluator:
        """Build a MetricsEvaluator with suite-level + item-level metrics."""
        all_metrics: List[base_metric.BaseMetric] = list(self._suite_regular_metrics)
        item_evaluators = _extract_item_evaluators(dataset_item_content)
        all_metrics.extend(item_evaluators)

        return metrics_evaluator.MetricsEvaluator(
            scoring_metrics=all_metrics,
            scoring_key_mapping=self._scoring_key_mapping,
        )

    @opik.track(name="metrics_calculation")  # type: ignore[attr-defined,has-type]
    def _compute_test_result_for_test_case(
        self,
        test_case_: test_case.TestCase,
        trial_id: int = 0,
    ) -> test_result.TestResult:
        item_evaluator = self._build_metrics_evaluator(test_case_.dataset_item_content)
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
        ignore_arguments=["test_case_"],
    )
    def _compute_scores_for_test_case_with_task_span(
        self,
        trace_id: str,
        task_span: models.SpanModel,
        test_case_: test_case.TestCase,
    ) -> List[score_result.ScoreResult]:
        assert self._task_span_evaluator is not None
        score_results, mapped_scoring_inputs = (
            self._task_span_evaluator.compute_task_span_scores(
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
        # Filter out evaluation config from task input (user shouldn't see it)
        task_input = {
            k: v for k, v in item_content.items() if k != EVALUATION_CONFIG_KEY
        }
        trace_data = trace.TraceData(
            input=task_input,
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
            LOGGER.debug("Task started, input: %s", task_input)
            try:
                task_output_ = task(task_input)
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
        dataset_items: Iterator[dataset_item.DatasetItem],
        task: LLMTask,
        experiment_: Optional[experiment.Experiment],
        trial_count: int,
        description: str,
        total_items: Optional[int] = None,
        default_execution_policy: Optional[ExecutionPolicy] = None,
    ) -> List[test_result.TestResult]:
        test_results: List[test_result.TestResult] = []

        # Build effective default policy:
        # - If explicit policy provided, use it
        # - Otherwise, use trial_count parameter (backward compatibility)
        effective_default_policy: ExecutionPolicy
        if default_execution_policy is not None:
            effective_default_policy = default_execution_policy
            initial_trial_count = default_execution_policy.get("runs_per_item", 1)
        else:
            effective_default_policy = {
                "runs_per_item": trial_count,
                "pass_threshold": 1,
            }
            initial_trial_count = trial_count

        # Cache dataset items and their runs_per_item for multiple trials
        dataset_items_cache: List[dataset_item.DatasetItem] = []
        item_runs_cache: List[int] = []
        max_runs_per_item = initial_trial_count

        # First pass: process all items for trial 0 and determine max runs_per_item
        desc = f"{description} trial 0" if initial_trial_count > 1 else description
        executor: evaluation_tasks_executor.StreamingExecutor[
            test_result.TestResult
        ] = evaluation_tasks_executor.StreamingExecutor(
            workers=self._workers,
            verbose=self._verbose,
            desc=desc,
            total=total_items,
        )
        with executor:
            for item in dataset_items:
                dataset_items_cache.append(item)
                item_content = item.get_content(include_id=False)
                item_policy = get_item_execution_policy(
                    item_content, effective_default_policy
                )
                item_runs = item_policy.get("runs_per_item", 1)
                item_runs_cache.append(item_runs)
                max_runs_per_item = max(max_runs_per_item, item_runs)

                # Store resolved execution policy only when using evaluation suites
                # (i.e., when default_execution_policy is explicitly provided)
                if default_execution_policy is not None:
                    if item.model_extra is None:
                        item.model_extra = {}
                    if EVALUATION_CONFIG_KEY not in item.model_extra:
                        item.model_extra[EVALUATION_CONFIG_KEY] = {}
                    item.model_extra[EVALUATION_CONFIG_KEY]["execution_policy"] = (
                        item_policy
                    )

                # Trial 0: always run (trial_id=0 < item_runs for any item_runs >= 1)
                evaluation_task = functools.partial(
                    self._compute_test_result_for_llm_task,
                    item=item,
                    task=task,
                    trial_id=0,
                    experiment_=experiment_,
                )
                executor.submit(evaluation_task)

            test_results += executor.get_results()

        # Subsequent trials: use cached items and their runs_per_item
        for trial_id in range(1, max_runs_per_item):
            desc = f"{description} trial {trial_id}"

            executor = evaluation_tasks_executor.StreamingExecutor(
                workers=self._workers,
                verbose=self._verbose,
                desc=desc,
                total=total_items,
            )
            with executor:
                for item, item_runs in zip(dataset_items_cache, item_runs_cache):
                    # Only run if this trial_id is within item's runs_per_item
                    if trial_id < item_runs:
                        evaluation_task = functools.partial(
                            self._compute_test_result_for_llm_task,
                            item=item,
                            task=task,
                            trial_id=trial_id,
                            experiment_=experiment_,
                        )
                        executor.submit(evaluation_task)

                test_results += executor.get_results()

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
        dataset_: Union[dataset.Dataset, dataset.DatasetVersion],
        task: LLMTask,
        nb_samples: Optional[int],
        dataset_item_ids: Optional[List[str]],
        dataset_sampler: Optional[samplers.BaseDatasetSampler],
        trial_count: int,
        experiment_: Optional[experiment.Experiment],
        dataset_filter_string: Optional[str] = None,
    ) -> List[test_result.TestResult]:
        # Extract suite config from dataset (set by create_evaluation_suite).
        # This includes evaluators and execution_policy for evaluation suites.
        default_execution_policy = dataset_.get_execution_policy()
        suite_evaluators = dataset_.get_evaluators()
        if suite_evaluators:
            self._suite_regular_metrics = (
                list(self._suite_regular_metrics) + suite_evaluators
            )

        # Can't use streaming with these parameters yet, so fallback to non-streaming
        use_streaming = dataset_sampler is None and not self._has_task_span_metrics

        # Get dataset items using streaming or non-streaming approach
        if use_streaming:
            dataset_items_iter = dataset_.__internal_api__stream_items_as_dataclasses__(
                nb_samples=nb_samples,
                dataset_item_ids=dataset_item_ids,
                batch_size=EVALUATION_STREAM_DATASET_BATCH_SIZE,
                filter_string=dataset_filter_string,
            )
        else:
            LOGGER.info("Dataset streaming disabled due to evaluation parameters")
            dataset_items_list = list(
                dataset_.__internal_api__stream_items_as_dataclasses__(
                    nb_samples=nb_samples,
                    dataset_item_ids=dataset_item_ids,
                    batch_size=EVALUATION_STREAM_DATASET_BATCH_SIZE,
                    filter_string=dataset_filter_string,
                )
            )

            if dataset_sampler is not None:
                dataset_items_list = dataset_sampler.sample(dataset_items_list)

            # Convert list to iterator
            dataset_items_iter = iter(dataset_items_list)

        # Calculate total items for progress bar
        if use_streaming:
            total_items = _calculate_total_items(
                dataset_=dataset_,
                nb_samples=nb_samples,
                dataset_item_ids=dataset_item_ids,
            )
        else:
            # After sampling, the actual count is the length of the list
            total_items = len(dataset_items_list)

        if not self._has_task_span_metrics:
            return self._compute_test_results_for_llm_task(
                dataset_items=dataset_items_iter,
                task=task,
                experiment_=experiment_,
                trial_count=trial_count,
                description="Evaluation",
                total_items=total_items,
                default_execution_policy=default_execution_policy,
            )

        LOGGER.debug(
            "Detected %d LLM task span scoring metrics — enabling handling of the LLM task evaluation span.",
            len(self._suite_task_span_metrics),
        )

        with local_recording.record_traces_locally(client=self._client) as recording:
            test_results = self._compute_test_results_for_llm_task(
                dataset_items=dataset_items_iter,
                task=task,
                experiment_=experiment_,
                trial_count=trial_count,
                description="Evaluation",
                total_items=total_items,
                default_execution_policy=default_execution_policy,
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
        dataset_items_list = [
            dataset_item.DatasetItem(
                id=f"temp_item_{idx}",
                **item,
            )
            for idx, item in enumerate(items)
        ]

        if not self._has_task_span_metrics:
            return self._compute_test_results_for_llm_task(
                dataset_items=iter(dataset_items_list),
                task=task,
                experiment_=None,
                trial_count=1,
                description="Items evaluation",
                total_items=len(items),
            )

        LOGGER.debug(
            "Detected %d LLM task span scoring metrics — enabling handling of the LLM task evaluation span.",
            len(self._suite_task_span_metrics),
        )

        with local_recording.record_traces_locally(client=self._client) as recording:
            test_results = self._compute_test_results_for_llm_task(
                dataset_items=iter(dataset_items_list),
                task=task,
                experiment_=None,
                trial_count=1,
                description="Items evaluation",
                total_items=len(items),
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

        test_results: List[test_result.TestResult] = evaluation_tasks_executor.execute(
            evaluation_tasks=evaluation_tasks,
            workers=self._workers,
            verbose=self._verbose,
        )

        return test_results
