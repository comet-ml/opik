from typing import Optional, List, Callable

from .. import asyncio_support
from ...api_objects import opik_client
from ...api_objects.threads import threads_client
from ..metrics.conversation import conversation_thread_metric
from . import evaluation_engine, evaluation_result
from opik.rest_api import JsonListStringPublic


def evaluate_threads(
    project_name: str,
    filter_string: Optional[str],
    eval_project_name: Optional[str],
    metrics: List[conversation_thread_metric.ConversationThreadMetric],
    trace_input_transform: Callable[[JsonListStringPublic], str],
    trace_output_transform: Callable[[JsonListStringPublic], str],
    verbose: int = 1,
    num_workers: int = 8,
    max_traces_per_thread: int = 1000,
) -> evaluation_result.ThreadsEvaluationResult:
    """Evaluate conversation threads using specified metrics.

    This function evaluates conversation threads from a project using the provided metrics.
    It creates a ThreadsEvaluationEngine to fetch threads matching the filter string,
    converts them to conversation threads, applies the metrics, and logs feedback scores.

    Args:
        project_name: The name of the project containing the threads to evaluate.
        filter_string: Optional filter string to select specific threads for evaluation.
            If None, all threads in the project will be evaluated.
        eval_project_name: Optional name for the evaluation project where evaluation traces will be stored.
            If None, the same project_name will be used.
        metrics: List of ConversationThreadMetric instances to apply to each thread.
            Must contain at least one metric.
        trace_input_transform: Function to transform trace input JSON to string representation.
            This is used when converting traces to conversation threads.
        trace_output_transform: Function to transform trace output JSON to string representation.
            This is used when converting traces to conversation threads.
        verbose: Verbosity level for progress reporting (0=silent, 1=progress).
            Default is 1.
        num_workers: Number of concurrent workers for thread evaluation.
            Default is 8.
        max_traces_per_thread: Maximum number of traces to fetch per thread.
            Default is 1000.

    Returns:
        ThreadsEvaluationResult containing evaluation scores for each thread.

    Raises:
        ValueError: If no metrics are provided.
        MetricComputationError: If no threads are found or if evaluation fails.

    Example:
        >>> from opik.evaluation import evaluate_threads
        >>> from opik.evaluation.metrics import ConversationalCoherenceMetric, UserFrustrationMetric
        >>>
        >>> # Initialize the evaluation metrics
        >>> conversation_coherence_metric = ConversationalCoherenceMetric()
        >>> user_frustration_metric = UserFrustrationMetric()
        >>>
        >>> # Run the threads evaluation
        >>> results = evaluate_threads(
        >>>     project_name="ai_team",
        >>>     filter_string='thread_id = "0197ad2a-cf5c-75af-be8b-20e8a23304fe"',
        >>>     eval_project_name="ai_team_evaluation",
        >>>     metrics=[
        >>>         conversation_coherence_metric,
        >>>         user_frustration_metric,
        >>>     ],
        >>>     trace_input_transform=lambda x: x["input"],
        >>>     trace_output_transform=lambda x: x["output"],
        >>> )
    """
    client = opik_client.get_client_cached()
    threads_client_ = threads_client.ThreadsClient(client)

    with asyncio_support.async_http_connections_expire_immediately():
        engine = evaluation_engine.ThreadsEvaluationEngine(
            client=threads_client_,
            project_name=project_name,
            number_of_workers=num_workers,
            verbose=verbose,
        )
        return engine.evaluate_threads(
            filter_string=filter_string,
            eval_project_name=eval_project_name,
            metrics=metrics,
            trace_input_transform=trace_input_transform,
            trace_output_transform=trace_output_transform,
            max_traces_per_thread=max_traces_per_thread,
        )
