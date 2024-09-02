import functools
import atexit
import datetime
import logging

from typing import Optional, Any, Dict, List
from ..types import SpanType, UsageDict, FeedbackScoreDict

from . import (
    span,
    trace,
    dataset,
    experiment,
    helpers,
    constants,
    validation_helpers,
)
from ..message_processing import streamer_constructors, messages
from ..rest_api import client as rest_api_client
from ..rest_api.types import dataset_public
from .. import datetime_helpers, config, httpx_client


LOGGER = logging.getLogger(__name__)


class Opik:
    def __init__(
        self,
        project_name: Optional[str] = None,
        workspace: Optional[str] = None,
        host: Optional[str] = None,
    ) -> None:
        """
        Initialize an Opik object that can be used to log traces and spans manually to Opik server.

        Args:
            project_name: The name of the project. If not provided, traces and spans will be logged to the `Default Project`.
            workspace: The name of the workspace. If not provided, `default` will be used.
            host: The host URL for the Opik server. If not provided, it will default to `http://localhost:5173/api`.
        Returns:
            None
        """
        config_ = config.get_from_user_inputs(
            project_name=project_name, workspace=workspace, host=host
        )
        self._workspace: str = config_.workspace
        self._project_name: str = config_.project_name
        self._flush_timeout: Optional[int] = config_.default_flush_timeout

        self._initialize_streamer(
            base_url=config_.url_override,
            workers=config_.background_workers,
            api_key=config_.api_key,
        )
        atexit.register(self.end, timeout=self._flush_timeout)

    def _initialize_streamer(
        self, base_url: str, workers: int, api_key: Optional[str]
    ) -> None:
        httpx_client_ = httpx_client.get(workspace=self._workspace, api_key=api_key)
        self._rest_client = rest_api_client.OpikApi(
            base_url=base_url,
            httpx_client=httpx_client_,
        )

        self._streamer = streamer_constructors.construct_online_streamer(
            n_consumers=workers,
            rest_client=self._rest_client,
        )

    def trace(
        self,
        id: Optional[str] = None,
        name: Optional[str] = None,
        start_time: Optional[datetime.datetime] = None,
        end_time: Optional[datetime.datetime] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        metadata: Optional[Dict[str, Any]] = None,
        tags: Optional[List[str]] = None,
    ) -> trace.Trace:
        """
        Create and log a new trace.

        Args:
            id: The unique identifier for the trace, if not provided a new ID will be generated. Must be a valid [UUIDv8](https://uuid.ramsey.dev/en/stable/rfc4122/version8.html) ID.
            name: The name of the trace.
            start_time: The start time of the trace. If not provided, the current local time will be used.
            end_time: The end time of the trace.
            input: The input data for the trace. This can be any valid JSON serializable object.
            output: The output data for the trace. This can be any valid JSON serializable object.
            metadata: Additional metadata for the trace. This can be any valid JSON serializable object.
            tags: Tags associated with the trace.

        Returns:
            trace.Trace: The created trace object.
        """
        id = id if id is not None else helpers.generate_id()
        start_time = (
            start_time if start_time is not None else datetime_helpers.local_timestamp()
        )
        create_trace_message = messages.CreateTraceMessage(
            trace_id=id,
            project_name=self._project_name,
            name=name,
            start_time=start_time,
            end_time=end_time,
            input=input,
            output=output,
            metadata=metadata,
            tags=tags,
        )
        self._streamer.put(create_trace_message)

        return trace.Trace(
            id=id,
            message_streamer=self._streamer,
            project_name=self._project_name,
        )

    def span(
        self,
        trace_id: Optional[str] = None,
        id: Optional[str] = None,
        parent_span_id: Optional[str] = None,
        name: Optional[str] = None,
        type: SpanType = "general",
        start_time: Optional[datetime.datetime] = None,
        end_time: Optional[datetime.datetime] = None,
        metadata: Optional[Dict[str, Any]] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        tags: Optional[List[str]] = None,
        usage: Optional[UsageDict] = None,
    ) -> span.Span:
        """
        Create and log a new span.

        Args:
            trace_id: The unique identifier for the trace. If not provided, a new ID will be generated. Must be a valid [UUIDv8](https://uuid.ramsey.dev/en/stable/rfc4122/version8.html) ID.
            id: The unique identifier for the span. If not provided, a new ID will be generated. Must be a valid [UUIDv8](https://uuid.ramsey.dev/en/stable/rfc4122/version8.html) ID.
            parent_span_id: The unique identifier for the parent span.
            name: The name of the span.
            type: The type of the span. Default is "general".
            start_time: The start time of the span. If not provided, the current local time will be used.
            end_time: The end time of the span.
            metadata: Additional metadata for the span. This can be any valid JSON serializable object.
            input: The input data for the span. This can be any valid JSON serializable object.
            output: The output data for the span. This can be any valid JSON serializable object.
            tags: Tags associated with the span.
            usage: Usage data for the span.

        Returns:
            span.Span: The created span object.
        """
        id = id if id is not None else helpers.generate_id()
        start_time = (
            start_time if start_time is not None else datetime_helpers.local_timestamp()
        )

        usage = validation_helpers.validate_usage_and_print_result(usage, LOGGER)

        if trace_id is None:
            trace_id = helpers.generate_id()
            # TODO: decide what needs to be passed to CreateTraceMessage.
            # This version is likely not final.
            create_trace_message = messages.CreateTraceMessage(
                trace_id=trace_id,
                project_name=self._project_name,
                name=name,
                start_time=start_time,
                end_time=end_time,
                input=input,
                output=output,
                metadata=metadata,
                tags=tags,
            )
            self._streamer.put(create_trace_message)

        create_span_message = messages.CreateSpanMessage(
            span_id=id,
            trace_id=trace_id,
            project_name=self._project_name,
            parent_span_id=parent_span_id,
            name=name,
            type=type,
            start_time=start_time,
            end_time=end_time,
            input=input,
            output=output,
            metadata=metadata,
            tags=tags,
            usage=usage,
        )
        self._streamer.put(create_span_message)

        return span.Span(
            id=id,
            parent_span_id=parent_span_id,
            trace_id=trace_id,
            project_name=self._project_name,
            message_streamer=self._streamer,
        )

    def log_spans_feedback_scores(self, scores: List[FeedbackScoreDict]) -> None:
        """
        Log feedback scores for spans.

        Args:
            scores (List[FeedbackScoreDict]): A list of feedback score dictionaries.

        Returns:
            None
        """
        valid_scores = [
            score
            for score in scores
            if validation_helpers.validate_feedback_score_and_print_result(
                score, LOGGER
            )
            is not None
        ]

        if len(valid_scores) == 0:
            return None

        score_messages = [
            messages.FeedbackScoreMessage(
                source=constants.FEEDBACK_SCORE_SOURCE_SDK,
                project_name=self._project_name,
                **score_dict,
            )
            for score_dict in valid_scores
        ]

        for batch in helpers.list_to_batches(
            score_messages, batch_size=constants.FEEDBACK_SCORES_MAX_BATCH_SIZE
        ):
            add_span_feedback_scores_batch_message = (
                messages.AddSpanFeedbackScoresBatchMessage(batch=batch)
            )

            self._streamer.put(add_span_feedback_scores_batch_message)

    def log_traces_feedback_scores(self, scores: List[FeedbackScoreDict]) -> None:
        """
        Log feedback scores for traces.

        Args:
            scores (List[FeedbackScoreDict]): A list of feedback score dictionaries.

        Returns:
            None
        """
        valid_scores = [
            score
            for score in scores
            if validation_helpers.validate_feedback_score_and_print_result(
                score, LOGGER
            )
            is not None
        ]

        if len(valid_scores) == 0:
            return None

        score_messages = [
            messages.FeedbackScoreMessage(
                source=constants.FEEDBACK_SCORE_SOURCE_SDK,
                project_name=self._project_name,
                **score_dict,
            )
            for score_dict in valid_scores
        ]
        for batch in helpers.list_to_batches(
            score_messages, batch_size=constants.FEEDBACK_SCORES_MAX_BATCH_SIZE
        ):
            add_span_feedback_scores_batch_message = (
                messages.AddTraceFeedbackScoresBatchMessage(batch=batch)
            )

            self._streamer.put(add_span_feedback_scores_batch_message)

    def get_dataset(self, name: str) -> dataset.Dataset:
        """
        Get dataset by name

        Args:
            name: The name of the dataset

        Returns:
            dataset.Dataset: dataset object associated with the name passed.
        """
        dataset_fern: dataset_public.DatasetPublic = (
            self._rest_client.datasets.get_dataset_by_identifier(dataset_name=name)
        )

        dataset_ = dataset.Dataset(
            name=name,
            description=dataset_fern.description,
            rest_client=self._rest_client,
        )

        return dataset_

    def delete_dataset(self, name: str) -> None:
        """
        Delete dataset by name

        Args:
            name: The name of the dataset
        """
        self._rest_client.datasets.delete_dataset_by_name(dataset_name=name)

    def create_dataset(
        self, name: str, description: Optional[str] = None
    ) -> dataset.Dataset:
        """
        Create a new dataset.

        Args:
            name: The name of the dataset.
            description: An optional description of the dataset.

        Returns:
            dataset.Dataset: The created dataset object.
        """
        self._rest_client.datasets.create_dataset(name=name, description=description)

        result = dataset.Dataset(
            name=name,
            description=description,
            rest_client=self._rest_client,
        )

        return result

    def create_experiment(self, name: str, dataset_name: str) -> experiment.Experiment:
        id = helpers.generate_id()
        self._rest_client.experiments.create_experiment(
            name=name, dataset_name=dataset_name, id=id
        )

        experiment_ = experiment.Experiment(
            id=id,
            name=name,
            dataset_name=dataset_name,
            rest_client=self._rest_client,
        )

        return experiment_

    def end(self, timeout: Optional[int] = None) -> None:
        """
        End the Opik session and submit all pending messages.

        Args:
            timeout (Optional[int]): The timeout for closing the streamer. Once the timeout is reached, the streamer will be closed regardless of whether all messages have been sent. If no timeout is set, the default value from the Opik configuration will be used.

        Returns:
            None
        """
        timeout = timeout if timeout is not None else self._flush_timeout
        self._streamer.close(timeout)

    def flush(self, timeout: Optional[int] = None) -> None:
        """
        Flush the streamer to ensure all messages are sent.

        Args:
            timeout (Optional[int]): The timeout for flushing the streamer. Once the timeout is reached, the flush method will return regardless of whether all messages have been sent.

        Returns:
            None
        """
        timeout = timeout if timeout is not None else self._flush_timeout
        self._streamer.flush(timeout)


@functools.lru_cache()
def get_client_cached() -> Opik:
    client = Opik()

    return client
