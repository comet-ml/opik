import functools
import logging
import time
from typing import List, Optional, Iterator, Callable

import opik
import opik.logging_messages as logging_messages
import opik.opik_context as opik_context
from opik.api_objects import opik_client, trace, local_recording
from opik.api_objects.dataset import dataset_item
from opik.api_objects.experiment import experiment
from opik.api_objects.dataset import execution_policy as dataset_execution_policy
from opik.evaluation import rest_operations, test_case, test_result
from opik.evaluation.suite_evaluators.agentic import context as agentic_context
from opik.evaluation.types import LLMTask, ScoringKeyMappingType
from opik.message_processing.emulation import models
from opik.message_processing.processors import message_processors_chain
from opik.types import TraceSource

from . import evaluation_tasks_executor, exception_analyzer, helpers, metrics_evaluator
from .types import EvaluationTask
from ..metrics import base_metric, score_result


LOGGER = logging.getLogger(__name__)

EVALUATION_TASK_NAME = "evaluation_task"

DEFAULT_STREAMER_DRAIN_TIMEOUT_SECONDS = 5.0


def get_item_execution_policy(
    item: dataset_item.DatasetItem,
    default_policy: dataset_execution_policy.ExecutionPolicy,
) -> dataset_execution_policy.ExecutionPolicy:
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
        source: TraceSource,
        flush_timeout: Optional[float] = None,
    ) -> None:
        self._client = client
        self._project_name = project_name
        self._workers = workers
        self._verbose = verbose
        self._source = source
        if flush_timeout is None:
            flush_timeout = DEFAULT_STREAMER_DRAIN_TIMEOUT_SECONDS
        self._flush_timeout = flush_timeout

    # --- Private: metrics & scoring ---

    @opik.track(  # type: ignore[attr-defined,has-type]
        name="metrics_calculation",
        ignore_arguments=[
            "regular_metrics",
            "scoring_key_mapping",
            "evaluator_model",
            # `trace_data` is the in-memory TraceData handed in by the
            # LLM-task path so the agentic judge can see the trace
            # before its CreateTraceMessage ships. It would otherwise
            # duplicate the wrapper trace's own data into this span's
            # input. See `_build_trace_tool_context`.
            "trace_data",
        ],
    )
    def _compute_test_result_for_test_case(
        self,
        test_case_: test_case.TestCase,
        regular_metrics: List[base_metric.BaseMetric],
        scoring_key_mapping: ScoringKeyMappingType,
        evaluator_model: Optional[str],
        trial_id: int = 0,
        trace_data: Optional[trace.TraceData] = None,
    ) -> test_result.TestResult:
        item_evaluator = metrics_evaluator.build_metrics_evaluator(
            item=test_case_.dataset_item,
            regular_metrics=regular_metrics,
            scoring_key_mapping=scoring_key_mapping,
            evaluator_model=evaluator_model,
        )
        # `trace_data` is the in-memory `TraceData` from the surrounding
        # `_compute_test_result_for_llm_task` call. When present, the
        # agentic context is built from it directly because the
        # `CreateTraceMessage` for this trace isn't emitted to the
        # emulator until the surrounding `evaluate_llm_task_context`
        # `__exit__` runs — i.e. after this scoring call returns.
        # See `build_trace_tool_context_from_trace_data` for the full
        # rationale.
        #
        # Re-scoring code paths (no live task) leave `trace_data=None`
        # and fall back to the lookup-based helper, which is correct
        # there because the trace was logged in a prior run.
        trace_tool_context = self._build_trace_tool_context(
            trace_id=test_case_.trace_id, trace_data=trace_data
        )
        score_results, mapped_scoring_inputs = item_evaluator.compute_regular_scores(
            dataset_item_content=test_case_.dataset_item_content,
            task_output=test_case_.task_output,
            trace_tool_context=trace_tool_context,
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

    def _build_trace_tool_context(
        self,
        trace_id: str,
        trace_data: Optional[trace.TraceData] = None,
    ) -> Optional[agentic_context.TraceToolContext]:
        """Return a TraceToolContext if the local emulator is active.

        Two construction paths depending on whether the trace has been
        logged yet:

        - **Live LLM-task scoring** (`trace_data` is provided): scoring
          runs *inside* `evaluate_llm_task_context`, before the trace's
          `CreateTraceMessage` is emitted. The emulator has the spans
          (emitted inline by `@opik.track`) but not the trace itself,
          so we synthesize a `TraceModel` from the in-memory
          `TraceData` and pull spans from the emulator. This is the
          only path that actually engages the agentic loop during a
          live run — see
          `build_trace_tool_context_from_trace_data` for the
          full ordering rationale.

        - **Re-scoring** (`trace_data` is None): the trace was logged
          in a previous run, so the emulator-lookup path is correct.
          Returns None if the trace is not in the emulator (the
          LLMJudge falls back to its one-shot path — see
          `LLMJudge.score`).

        Returns None when the emulator is not in the processor chain
        or is inactive, regardless of which construction path applies.
        """
        # `getattr` with a default keeps this MagicMock-friendly for
        # the unit tests in `test_evaluate_test_suite.py`: MagicMock
        # auto-rejects attribute names that look like dunders (start
        # and end with `__`), so plain attribute access would raise
        # AttributeError on mocked clients. Production clients always
        # have this attribute, so the default branch never fires there.
        chain = getattr(self._client, "__internal_api__message_processor__", None)
        if chain is None:
            return None
        emulator = message_processors_chain.get_local_emulator_message_processor(chain)
        if emulator is None or not emulator.is_active():
            return None
        # Drain pending messages so spans emitted by `@opik.track`
        # during the just-run task body (including the error_info on
        # any span whose function raised) have been applied to the
        # emulator before we read them. `client.__internal_api__span__`
        # only *submits* to the streamer queue — the consumer thread
        # processes asynchronously, and without this drain there's a
        # small window where the agentic judge sees a stale view.
        # Bounded timeout so a stuck consumer doesn't block scoring
        # forever; on timeout we fall through with whatever state is
        # currently in the emulator (best-effort).
        drained = self._client.__internal_api__drain_to_processors__(
            timeout=self._flush_timeout
        )
        if not drained:
            LOGGER.debug(
                "[engine] streamer drain timed out before agentic context build; "
                "agentic judge may see partial trace state for trace %s",
                trace_id,
            )
        # Force trace_trees rebuild so spans logged in this process are
        # attached to the trace before we copy them into the context.
        # This is needed regardless of construction path — the spans
        # are what we ultimately read.
        _ = emulator.trace_trees
        if trace_data is not None:
            return agentic_context.build_trace_tool_context_from_trace_data(
                trace_data=trace_data, emulator=emulator
            )
        return agentic_context.build_trace_tool_context(
            trace_id=trace_id, emulator=emulator
        )

    # --- Private: task execution ---

    def _compute_test_result_for_llm_task(
        self,
        item: dataset_item.DatasetItem,
        task: LLMTask,
        trial_id: int,
        experiment_: Optional[experiment.Experiment],
        regular_metrics: List[base_metric.BaseMetric],
        scoring_key_mapping: ScoringKeyMappingType,
        evaluator_model: Optional[str],
    ) -> test_result.TestResult:
        if not hasattr(task, "opik_tracked"):
            name = task.__name__ if hasattr(task, "__name__") else "llm_task"
            task = opik.track(name=name, source=self._source)(task)  # type: ignore[attr-defined,has-type]

        item_content = item.get_content(include_id=True)
        trace_data = trace.TraceData(
            input=item_content,
            name=EVALUATION_TASK_NAME,
            created_by="evaluation",
            project_name=self._project_name,
            source=self._source,
        )

        execution_policy_dict = None
        if item.execution_policy is not None:
            execution_policy_dict = item.execution_policy.model_dump(exclude_none=True)

        with helpers.evaluate_llm_task_context(
            experiment=experiment_,
            dataset_item_id=item.id,
            trace_data=trace_data,
            client=self._client,
            execution_policy=execution_policy_dict or None,
        ):
            LOGGER.debug("[engine] Task started for item %s", item.id)
            task_start = time.perf_counter()
            try:
                task_output_ = task(item_content)
            except Exception as exception:
                LOGGER.error("[engine] Task failed for item %s: %s", item.id, exception)
                if exception_analyzer.is_llm_provider_rate_limit_error(exception):
                    LOGGER.error(
                        logging_messages.LLM_PROVIDER_RATE_LIMIT_ERROR_DETECTED_IN_EVALUATE_FUNCTION
                    )

                raise
            task_execution_time = time.perf_counter() - task_start
            LOGGER.debug(
                "[engine] Task done for item %s in %.1fs", item.id, task_execution_time
            )

            opik_context.update_current_trace(output=task_output_)

            test_case_ = test_case.TestCase(
                trace_id=trace_data.id,
                dataset_item_id=item.id,
                task_output=task_output_,
                dataset_item_content=item_content,
                dataset_item=item,
            )
            LOGGER.debug("[engine] Scoring started for item %s", item.id)
            scoring_start = time.perf_counter()
            test_result_ = self._compute_test_result_for_test_case(
                test_case_=test_case_,
                regular_metrics=regular_metrics,
                scoring_key_mapping=scoring_key_mapping,
                evaluator_model=evaluator_model,
                trial_id=trial_id,
                # Hand the in-memory TraceData to scoring so the agentic
                # judge sees the trace before its CreateTraceMessage has
                # been emitted to the emulator (the emit happens in the
                # surrounding context-manager's `__exit__`, after we
                # return). See `_build_trace_tool_context` for details.
                trace_data=trace_data,
            )
            test_result_.task_execution_time = task_execution_time
            test_result_.scoring_time = time.perf_counter() - scoring_start
            LOGGER.debug(
                "[engine] Scoring done for item %s in %.1fs",
                item.id,
                test_result_.scoring_time,
            )

        return test_result_

    # --- Private: parallel execution ---

    def _compute_test_results_with_execution_policy(
        self,
        dataset_items: Iterator[dataset_item.DatasetItem],
        task: LLMTask,
        experiment_: Optional[experiment.Experiment],
        regular_metrics: List[base_metric.BaseMetric],
        scoring_key_mapping: ScoringKeyMappingType,
        evaluator_model: Optional[str],
        description: str,
        total_items: Optional[int],
        default_execution_policy: dataset_execution_policy.ExecutionPolicy,
        show_scores_in_progress_bar: bool,
    ) -> List[test_result.TestResult]:
        """
        Execute tasks with full parallelism and item-based progress.

        Uses StreamingExecutor with group_id to track progress per item
        (not per run), so that runs_per_item > 1 shows correct item count.
        """
        with evaluation_tasks_executor.StreamingExecutor[test_result.TestResult](
            workers=self._workers,
            verbose=self._verbose,
            client=self._client,
            desc=description,
            total=total_items,
            show_score_postfix=show_scores_in_progress_bar,
        ) as executor:
            for item in dataset_items:
                item_policy = get_item_execution_policy(item, default_execution_policy)
                item_runs = item_policy.get("runs_per_item", 1)

                # Store resolved execution policy
                item.execution_policy = dataset_item.ExecutionPolicyItem(
                    runs_per_item=item_runs,
                    pass_threshold=item_policy.get("pass_threshold", 1),
                )

                # Declare group size before submitting to avoid race with callbacks
                executor.set_group_size(item.id, item_runs)

                # Submit all runs for this item
                for run_id in range(item_runs):
                    executor.submit(
                        functools.partial(
                            self._compute_test_result_for_llm_task,
                            item=item,
                            task=task,
                            trial_id=run_id,
                            experiment_=experiment_,
                            regular_metrics=regular_metrics,
                            scoring_key_mapping=scoring_key_mapping,
                            evaluator_model=evaluator_model,
                        ),
                        group_id=item.id,
                    )

            return executor.get_results()

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
                source=self._source,
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
            client=self._client,
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
        scoring_key_mapping: ScoringKeyMappingType,
        evaluator_model: Optional[str],
        description: str,
        total_items: Optional[int],
        default_execution_policy: dataset_execution_policy.ExecutionPolicy,
        show_scores_in_progress_bar: bool,
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
            show_scores_in_progress_bar=show_scores_in_progress_bar,
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

    def run_and_score(
        self,
        dataset_items: Iterator[dataset_item.DatasetItem],
        task: LLMTask,
        scoring_metrics: List[base_metric.BaseMetric],
        scoring_key_mapping: Optional[ScoringKeyMappingType],
        evaluator_model: Optional[str],
        experiment_: Optional[experiment.Experiment],
        default_execution_policy: dataset_execution_policy.ExecutionPolicy,
        total_items: Optional[int],
        description: str = "Evaluation",
        show_scores_in_progress_bar: bool = True,
    ) -> List[test_result.TestResult]:
        """
        Run a task on dataset items in parallel, then score results with metrics.

        This is the universal entry point for all evaluation flows that involve
        running a task. The caller is responsible for resolving dataset items
        and building the execution policy.
        """
        resolved_scoring_key_mapping: ScoringKeyMappingType = (
            scoring_key_mapping if scoring_key_mapping is not None else {}
        )

        regular_metrics, task_span_metrics = (
            metrics_evaluator.split_into_regular_and_task_span_metrics(scoring_metrics)
        )

        return self._execute_evaluation(
            dataset_items=dataset_items,
            task=task,
            experiment_=experiment_,
            regular_metrics=regular_metrics,
            task_span_metrics=task_span_metrics,
            scoring_key_mapping=resolved_scoring_key_mapping,
            evaluator_model=evaluator_model,
            description=description,
            total_items=total_items,
            default_execution_policy=default_execution_policy,
            show_scores_in_progress_bar=show_scores_in_progress_bar,
        )

    def score_test_cases(
        self,
        test_cases: List[test_case.TestCase],
        scoring_metrics: List[base_metric.BaseMetric],
        scoring_key_mapping: Optional[ScoringKeyMappingType],
    ) -> List[test_result.TestResult]:
        """
        Score existing test cases with metrics (no task execution).

        This is the universal entry point for re-scoring existing experiment
        results with new metrics.
        """
        resolved_scoring_key_mapping: ScoringKeyMappingType = (
            scoring_key_mapping if scoring_key_mapping is not None else {}
        )

        regular_metrics, _ = metrics_evaluator.split_into_regular_and_task_span_metrics(
            scoring_metrics
        )

        evaluation_tasks: List[EvaluationTask[test_result.TestResult]] = [
            functools.partial(
                self._compute_test_result_for_test_case,
                test_case_=test_case_,
                regular_metrics=regular_metrics,
                scoring_key_mapping=resolved_scoring_key_mapping,
                evaluator_model=None,
            )
            for test_case_ in test_cases
        ]

        test_results: List[test_result.TestResult] = evaluation_tasks_executor.execute(
            evaluation_tasks=evaluation_tasks,
            workers=self._workers,
            verbose=self._verbose,
            client=self._client,
        )

        return test_results
