import functools
import logging
from typing import Optional, List, Callable, Dict, Literal

import opik
import opik.exceptions as exceptions
import opik.opik_context as opik_context
from opik.evaluation.metrics.conversation import conversation_thread_metric
from opik.rest_api import JsonListStringPublic, TraceThread

from . import evaluation_result, helpers
from ..engine import evaluation_tasks_executor
from ..engine import types as engine_types
from ..metrics import score_result
from ...api_objects import trace
from ...api_objects.threads import threads_client

LOGGER = logging.getLogger(__name__)


class ThreadsEvaluationEngine:
    def __init__(
        self,
        client: threads_client.ThreadsClient,
        project_name: Optional[str],
        number_of_workers: int,
        verbose: int,
    ) -> None:
        self._client = client
        self._project_name = project_name
        self._number_of_workers = number_of_workers
        self._verbose = verbose

        self._threads_client = client

    def evaluate_threads(
        self,
        filter_string: Optional[str],
        eval_project_name: Optional[str],
        metrics: List[conversation_thread_metric.ConversationThreadMetric],
        trace_input_transform: Callable[[JsonListStringPublic], str],
        trace_output_transform: Callable[[JsonListStringPublic], str],
        max_traces_per_thread: int = 1000,
    ) -> evaluation_result.ThreadsEvaluationResult:
        if len(metrics) == 0:
            raise ValueError("No metrics provided")

        threads = self._threads_client.search_threads(
            project_name=self._project_name,
            filter_string=filter_string,
        )
        if len(threads) == 0:
            raise exceptions.EvaluationError(
                f"No threads found with filter_string: {filter_string}"
            )

        inactive_threads = [thread for thread in threads if thread.status == "inactive"]
        if len(inactive_threads) == 0:
            raise exceptions.EvaluationError(
                f"No closed threads found with filter_string: {filter_string}. Only closed threads can be evaluated."
            )
        elif len(inactive_threads) < len(threads):
            active_threads_ids = [
                thread.id for thread in threads if thread.status == "active"
            ]
            inactive_threads_ids = [thread.id for thread in inactive_threads]
            LOGGER.warning(
                f"Some threads are active: {active_threads_ids} with filter_string: {filter_string}. Only closed threads will be evaluated: {inactive_threads_ids}."
            )

        evaluation_tasks: List[
            engine_types.EvaluationTask[evaluation_result.ThreadEvaluationResult]
        ] = [
            functools.partial(
                self.evaluate_thread,
                thread=thread,
                eval_project_name=eval_project_name,
                metrics=metrics,
                trace_input_transform=trace_input_transform,
                trace_output_transform=trace_output_transform,
                max_traces_per_thread=max_traces_per_thread,
            )
            for thread in inactive_threads
        ]

        results = evaluation_tasks_executor.execute(
            evaluation_tasks, workers=self._number_of_workers, verbose=self._verbose
        )

        helpers.log_feedback_scores(
            results, project_name=self._project_name, client=self._threads_client
        )

        return evaluation_result.ThreadsEvaluationResult(results=results)

    def evaluate_thread(
        self,
        thread: TraceThread,
        eval_project_name: Optional[str],
        metrics: List[conversation_thread_metric.ConversationThreadMetric],
        trace_input_transform: Callable[[JsonListStringPublic], str],
        trace_output_transform: Callable[[JsonListStringPublic], str],
        max_traces_per_thread: int,
    ) -> evaluation_result.ThreadEvaluationResult:
        conversation_dict = helpers.load_conversation_thread(
            thread=thread,
            trace_input_transform=trace_input_transform,
            trace_output_transform=trace_output_transform,
            max_results=max_traces_per_thread,
            project_name=self._project_name,
            client=self._client.opik_client,
        ).model_dump()

        conversation = conversation_dict["discussion"]
        if len(conversation) == 0:
            LOGGER.warning(
                f"Thread '{thread.id}' has no conversation traces. Skipping evaluation."
            )
            return evaluation_result.ThreadEvaluationResult(
                thread_id=thread.id, scores=[]
            )

        if eval_project_name is None:
            eval_project_name = self._project_name

        # Create a new trace for the evaluation
        trace_data = trace.TraceData(
            input={"conversation": conversation, "metrics": metrics},
            name="evaluation_task",
            created_by="evaluation",
            project_name=eval_project_name,
        )

        with opik_context.trace_context(
            trace_data=trace_data,
            client=self._client.opik_client,
        ):
            results = self._evaluate_conversation(conversation, metrics)

            # Update the current trace with the evaluation results
            outputs = [result.__dict__ for result in results]
            opik_context.update_current_trace(output={"evaluation_results": outputs})

        return evaluation_result.ThreadEvaluationResult(
            thread_id=thread.id,
            scores=results,
        )

    @opik.track(name="metrics_calculation")  # type: ignore[attr-defined,has-type]
    def _evaluate_conversation(
        self,
        conversation: List[Dict[Literal["role", "content"], str]],
        metrics: List[conversation_thread_metric.ConversationThreadMetric],
    ) -> List[score_result.ScoreResult]:
        score_results: List[score_result.ScoreResult] = []
        for metric in metrics:
            try:
                LOGGER.debug("Metric %s score started", metric.name)
                result = metric.score(conversation)
                LOGGER.debug("Metric %s score ended", metric.name)

                if isinstance(result, list):
                    score_results.extend(result)
                else:
                    score_results.append(result)
            except Exception as e:
                LOGGER.error(
                    "Failed to compute metric %s. Score result will be marked as failed.",
                    metric.name,
                    exc_info=True,
                )
                score_results.append(
                    score_result.ScoreResult(
                        name=metric.name,
                        value=0.0,
                        reason=str(e),
                        scoring_failed=True,
                    )
                )

        return score_results
