import functools
from typing import Optional, List, Callable

from opik.api_objects import opik_client
from opik.evaluation.metrics.conversation import conversation_thread_metric
from opik.rest_api import JsonListStringPublic, TraceThread
from . import _types
from . import evaluation_result, evaluation_executor
from ...api_objects.conversation import conversation_factory, conversation_thread
from ...api_objects.threads import threads_client
from ...types import FeedbackScoreDict


class ThreadsEvaluationEngine:
    def __init__(
        self,
        client: opik_client.Opik,
        project_name: Optional[str],
        number_of_workers: int,
        verbose: int,
    ) -> None:
        self._client = client
        self._project_name = project_name
        self._number_of_workers = number_of_workers
        self._verbose = verbose

        self._threads_client = threads_client.ThreadsClient(self._client)

    def evaluate_threads(
        self,
        filter_string: Optional[str],
        eval_project_name: Optional[str],
        metrics: List[conversation_thread_metric.ConversationThreadMetric],
        trace_input_transform: Callable[[JsonListStringPublic], str],
        trace_output_transform: Callable[[JsonListStringPublic], str],
        max_traces_per_thread: int = 1000,
    ) -> evaluation_result.ThreadsEvaluationResult:
        threads = self._get_threads(filter_string)

        evaluation_tasks: List[_types.EvaluationTask] = [
            functools.partial(
                self._evaluate_thread,
                thread=thread,
                eval_project_name=eval_project_name,
                metrics=metrics,
                trace_input_transform=trace_input_transform,
                trace_output_transform=trace_output_transform,
                max_traces_per_thread=max_traces_per_thread,
            )
            for thread in threads
        ]

        results = evaluation_executor.execute(
            evaluation_tasks, self._number_of_workers, self._verbose
        )

        self._log_feedback_scores(results)

        return results

    def _evaluate_thread(
        self,
        thread: TraceThread,
        eval_project_name: Optional[str],
        metrics: List[conversation_thread_metric.ConversationThreadMetric],
        trace_input_transform: Callable[[JsonListStringPublic], str],
        trace_output_transform: Callable[[JsonListStringPublic], str],
        max_traces_per_thread: int,
    ) -> _types.ThreadTestResult:
        conversation_dict = self._get_conversation_tread(
            thread=thread,
            trace_input_transform=trace_input_transform,
            trace_output_transform=trace_output_transform,
            max_results=max_traces_per_thread,
        ).model_dump()

        # start trace for a thread
        if eval_project_name is None:
            eval_project_name = self._project_name

        trace = self._client.trace(
            name=f"thread_id: {thread.id} evaluation",
            project_name=eval_project_name,
            input=conversation_dict,
        )

        results = []
        input_dict = thread.model_dump()
        for metric in metrics:
            # start span
            span = trace.span(
                name=metric.name,
                input=input_dict,
            )

            result = metric.score(conversation_dict["discussion"])
            results.append(result)

            # end span
            self._client.span(
                trace_id=trace.id,
                id=span.id,
                output=result.__dict__,
            )

        # end trace
        outputs = [result.__dict__ for result in results]
        self._client.trace(
            id=trace.id,
            output={"evaluation_results": outputs},
        )

        return _types.ThreadTestResult(thread_id=thread.id, scores=results)

    def _get_threads(self, filter_string: Optional[str]) -> List[TraceThread]:
        return self._threads_client.search_threads(
            project_name=self._project_name,
            filter_string=filter_string,
        )

    def _get_conversation_tread(
        self,
        thread: TraceThread,
        trace_input_transform: Callable[[JsonListStringPublic], str],
        trace_output_transform: Callable[[JsonListStringPublic], str],
        max_results: int,
    ) -> conversation_thread.ConversationThread:
        traces = self._client.search_traces(
            project_name=self._project_name,
            filter_string=f"thread_id = {thread.id}",
            max_results=max_results,
        )
        return conversation_factory.create_conversation_from_traces(
            traces=traces,
            input_transform=trace_input_transform,
            output_transform=trace_output_transform,
        )

    def _log_feedback_scores(
        self,
        results: evaluation_result.ThreadsEvaluationResult,
    ) -> None:
        for thread_id, scores in results.results.items():
            feedback_scores = [
                FeedbackScoreDict(
                    id=thread_id,
                    name=score.name,
                    value=score.value,
                    reason=score.reason,
                )
                for score in scores
            ]
            self._threads_client.log_threads_feedback_scores(
                scores=feedback_scores,
                project_name=self._project_name,
            )
