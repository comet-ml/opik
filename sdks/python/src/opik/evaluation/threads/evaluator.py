from typing import Optional, List, Callable

from .. import asyncio_support
from ...api_objects import opik_client
from ...api_objects.threads import threads_client
from ..metrics.conversation import conversation_thread_metric
from . import evaluation_engine, evaluation_result
from opik.rest_api import JsonListStringPublic, TracePublic


def evaluate_threads(
    project_name: str,
    filter_string: Optional[str],
    eval_project_name: Optional[str],
    metrics: List[conversation_thread_metric.ConversationThreadMetric],
    trace_input_transform: Callable[[JsonListStringPublic], str],
    trace_output_transform: Callable[[JsonListStringPublic], str],
    trace_context_transform: Optional[
        Callable[[TracePublic], Optional[List[str]]]
    ] = None,
    *,
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
        filter_string: Optional filter string to select specific threads for evaluation using Opik Query Language (OQL).
            The format is: "<COLUMN> <OPERATOR> <VALUE> [AND <COLUMN> <OPERATOR> <VALUE>]*"

            Supported columns include:
            - `id`, `name`, `created_by`, `thread_id`, `type`, `model`, `provider`: String fields with full operator support
            - `status`: String field (=, contains, not_contains only)
            - `start_time`, `end_time`: DateTime fields (use ISO 8601 format, e.g., "2024-01-01T00:00:00Z")
            - `input`, `output`: String fields for content (=, contains, not_contains only)
            - `metadata`: Dictionary field (use dot notation, e.g., "metadata.model")
            - `feedback_scores`: Numeric field (use dot notation, e.g., "feedback_scores.accuracy")
            - `tags`: List field (use "contains" operator only)
            - `usage.total_tokens`, `usage.prompt_tokens`, `usage.completion_tokens`: Numeric usage fields
            - `duration`, `number_of_messages`, `total_estimated_cost`: Numeric fields

            Examples: 'id = "thread_123"', 'duration > 300', 'tags contains "prod"'
            If None, all threads in the project will be evaluated.
        eval_project_name: Optional name for the evaluation project where evaluation traces will be stored.
            If None, the same project_name will be used.
        metrics: List of ConversationThreadMetric instances to apply to each thread.
            Must contain at least one metric.
        trace_input_transform: Function to transform trace input JSON to string representation.
            This function extracts the relevant user message from your trace's input structure.
            The function receives the raw trace input as a dictionary and should return a string.

            Example: If your trace input is {"content": {"user_question": "Hello"}},
            use: lambda x: x["content"]["user_question"]

            This transformation is essential because trace inputs vary by framework, but metrics
            expect a standardized string format representing the user's message.
        trace_output_transform: Function to transform trace output JSON to string representation.
            This function extracts the relevant agent response from your trace's output structure.
            The function receives the raw trace output as a dictionary and should return a string.

            Example: If your trace output is {"response": {"text": "Hi there"}},
            use: lambda x: x["response"]["text"]

            This transformation is essential because trace outputs vary by framework, but metrics
            expect a standardized string format representing the agent's response.
        trace_context_transform: Optional function extracting the context the agent response was
            grounded on, e.g. the documents retrieved by a RAG pipeline for that turn.
            Unlike the two transforms above, it receives the **whole trace object**, because
            the context can be logged anywhere: `trace.metadata`, `trace.output` or `trace.input`.
            It should return a list of strings, or None when the trace has no context.

            Example: If you log the retrieved documents as trace metadata,
            use: lambda trace: trace.metadata["retrieved_docs"]

            The extracted context is attached to the agent message of the same trace.
            `ConversationalCoherenceMetric` is context aware: it shows the documents to
            the judge, so a turn scores only when the answer is both relevant and
            supported by them. Metrics that don't need the context ignore it.
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

    Example:
        >>> # Evaluating a RAG agent: ConversationalCoherenceMetric additionally checks
        >>> # each answer against the documents it was generated from.
        >>> from opik.evaluation import evaluate_threads
        >>> from opik.evaluation.metrics import ConversationalCoherenceMetric
        >>>
        >>> results = evaluate_threads(
        >>>     project_name="ai_team",
        >>>     filter_string=None,
        >>>     eval_project_name="ai_team_evaluation",
        >>>     metrics=[ConversationalCoherenceMetric()],
        >>>     trace_input_transform=lambda x: x["input"],
        >>>     trace_output_transform=lambda x: x["output"],
        >>>     trace_context_transform=lambda trace: trace.metadata["retrieved_docs"],
        >>> )
    """
    client = opik_client.get_global_client()
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
            trace_context_transform=trace_context_transform,
            max_traces_per_thread=max_traces_per_thread,
        )
