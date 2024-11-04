import functools
import atexit
import datetime
import logging

from typing import Optional, Any, Dict, List, Mapping

from ..types import SpanType, UsageDict, FeedbackScoreDict
from . import (
    opik_query_language,
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
from ..rest_api.types import dataset_public, trace_public, span_public, project_public
from ..rest_api.core.api_error import ApiError
from .. import datetime_helpers, config, httpx_client, jsonable_encoder, url_helpers


LOGGER = logging.getLogger(__name__)


class Opik:
    def __init__(
        self,
        project_name: Optional[str] = None,
        workspace: Optional[str] = None,
        host: Optional[str] = None,
        _use_batching: bool = False,
    ) -> None:
        """
        Initialize an Opik object that can be used to log traces and spans manually to Opik server.

        Args:
            project_name: The name of the project. If not provided, traces and spans will be logged to the `Default Project`.
            workspace: The name of the workspace. If not provided, `default` will be used.
            host: The host URL for the Opik server. If not provided, it will default to `https://www.comet.com/opik/api`.
            _use_batching: intended for internal usage in specific conditions only.
                Enabling it is unsafe and can lead to data loss.
        Returns:
            None
        """
        config_ = config.get_from_user_inputs(
            project_name=project_name, workspace=workspace, url_override=host
        )
        self._workspace: str = config_.workspace
        self._project_name: str = config_.project_name
        self._flush_timeout: Optional[int] = config_.default_flush_timeout
        self._project_name_most_recent_trace: Optional[str] = None

        self._initialize_streamer(
            base_url=config_.url_override,
            workers=config_.background_workers,
            api_key=config_.api_key,
            use_batching=_use_batching,
        )
        atexit.register(self.end, timeout=self._flush_timeout)

    def _initialize_streamer(
        self,
        base_url: str,
        workers: int,
        api_key: Optional[str],
        use_batching: bool,
    ) -> None:
        httpx_client_ = httpx_client.get(workspace=self._workspace, api_key=api_key)
        self._rest_client = rest_api_client.OpikApi(
            base_url=base_url,
            httpx_client=httpx_client_,
        )

        self._streamer = streamer_constructors.construct_online_streamer(
            n_consumers=workers,
            rest_client=self._rest_client,
            use_batching=use_batching,
        )

    def _display_trace_url(self, workspace: str, project_name: str) -> None:
        projects_url = url_helpers.get_projects_url(workspace=workspace)

        if (
            self._project_name_most_recent_trace is None
            or self._project_name_most_recent_trace != project_name
        ):
            LOGGER.info(
                f'Started logging traces to the "{project_name}" project at {projects_url}.'
            )
            self._project_name_most_recent_trace = project_name

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
        feedback_scores: Optional[List[FeedbackScoreDict]] = None,
        project_name: Optional[str] = None,
        **ignored_kwargs: Any,
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
            feedback_scores: The list of feedback score dicts associated with the trace. Dicts don't require to have an `id` value.
            project_name: The name of the project. If not set, the project name which was configured when Opik instance
                was created will be used.

        Returns:
            trace.Trace: The created trace object.
        """
        id = id if id is not None else helpers.generate_id()
        start_time = (
            start_time if start_time is not None else datetime_helpers.local_timestamp()
        )

        if project_name is None:
            project_name = self._project_name

        create_trace_message = messages.CreateTraceMessage(
            trace_id=id,
            project_name=project_name,
            name=name,
            start_time=start_time,
            end_time=end_time,
            input=input,
            output=output,
            metadata=metadata,
            tags=tags,
        )
        self._streamer.put(create_trace_message)
        self._display_trace_url(workspace=self._workspace, project_name=project_name)

        if feedback_scores is not None:
            for feedback_score in feedback_scores:
                feedback_score["id"] = id

            self.log_traces_feedback_scores(feedback_scores, project_name)

        return trace.Trace(
            id=id,
            message_streamer=self._streamer,
            project_name=project_name,
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
        feedback_scores: Optional[List[FeedbackScoreDict]] = None,
        project_name: Optional[str] = None,
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
            feedback_scores: The list of feedback score dicts associated with the span. Dicts don't require to have an `id` value.
            project_name: The name of the project. If not set, the project name which was configured when Opik instance
                was created will be used.

        Returns:
            span.Span: The created span object.
        """
        id = id if id is not None else helpers.generate_id()
        start_time = (
            start_time if start_time is not None else datetime_helpers.local_timestamp()
        )

        parsed_usage = validation_helpers.validate_and_parse_usage(usage, LOGGER)
        if parsed_usage.full_usage is not None:
            metadata = (
                {"usage": parsed_usage.full_usage}
                if metadata is None
                else {"usage": parsed_usage.full_usage, **metadata}
            )

        if project_name is None:
            project_name = self._project_name

        if trace_id is None:
            trace_id = helpers.generate_id()
            # TODO: decide what needs to be passed to CreateTraceMessage.
            # This version is likely not final.
            create_trace_message = messages.CreateTraceMessage(
                trace_id=trace_id,
                project_name=project_name,
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
            project_name=project_name,
            parent_span_id=parent_span_id,
            name=name,
            type=type,
            start_time=start_time,
            end_time=end_time,
            input=input,
            output=output,
            metadata=metadata,
            tags=tags,
            usage=parsed_usage.supported_usage,
        )
        self._streamer.put(create_span_message)

        if feedback_scores is not None:
            for feedback_score in feedback_scores:
                feedback_score["id"] = id

            self.log_spans_feedback_scores(feedback_scores, project_name)

        return span.Span(
            id=id,
            parent_span_id=parent_span_id,
            trace_id=trace_id,
            project_name=project_name,
            message_streamer=self._streamer,
        )

    def log_spans_feedback_scores(
        self, scores: List[FeedbackScoreDict], project_name: Optional[str] = None
    ) -> None:
        """
        Log feedback scores for spans.

        Args:
            scores (List[FeedbackScoreDict]): A list of feedback score dictionaries.
                Specifying a span id via `id` key for each score is mandatory.
            project_name: The name of the project in which the spans are logged. If not set, the project name
                which was configured when Opik instance was created will be used.

        Returns:
            None
        """
        valid_scores = [
            score
            for score in scores
            if validation_helpers.validate_feedback_score(score, LOGGER) is not None
        ]

        if len(valid_scores) == 0:
            return None

        score_messages = [
            messages.FeedbackScoreMessage(
                source=constants.FEEDBACK_SCORE_SOURCE_SDK,
                project_name=project_name or self._project_name,
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

    def log_traces_feedback_scores(
        self, scores: List[FeedbackScoreDict], project_name: Optional[str] = None
    ) -> None:
        """
        Log feedback scores for traces.

        Args:
            scores (List[FeedbackScoreDict]): A list of feedback score dictionaries.
                Specifying a trace id via `id` key for each score is mandatory.
            project_name: The name of the project in which the traces are logged. If not set, the project name
                which was configured when Opik instance was created will be used.

        Returns:
            None
        """
        valid_scores = [
            score
            for score in scores
            if validation_helpers.validate_feedback_score(score, LOGGER) is not None
        ]

        if len(valid_scores) == 0:
            return None

        score_messages = [
            messages.FeedbackScoreMessage(
                source=constants.FEEDBACK_SCORE_SOURCE_SDK,
                project_name=project_name or self._project_name,
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

        dataset_.__internal_api__sync_hashes__()

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

    def get_or_create_dataset(
        self, name: str, description: Optional[str] = None
    ) -> dataset.Dataset:
        """
        Get an existing dataset by name or create a new one if it does not exist.

        Args:
            name: The name of the dataset.
            description: An optional description of the dataset.

        Returns:
            dataset.Dataset: The dataset object.
        """
        try:
            return self.get_dataset(name)
        except ApiError as e:
            if e.status_code == 404:
                return self.create_dataset(name, description)
            raise

    def create_experiment(
        self,
        dataset_name: str,
        name: Optional[str] = None,
        experiment_config: Optional[Dict[str, Any]] = None,
    ) -> experiment.Experiment:
        """
        Creates a new experiment using the given dataset name and optional parameters.

        Args:
            dataset_name (str): The name of the dataset to associate with the experiment.
            name (Optional[str]): The optional name for the experiment. If None, a generated name will be used.
            experiment_config (Optional[Dict[str, Any]]): Optional experiment configuration parameters. Must be a dictionary if provided.

        Returns:
            experiment.Experiment: The newly created experiment object.
        """
        id = helpers.generate_id()

        if isinstance(experiment_config, Mapping):
            metadata = jsonable_encoder.jsonable_encoder(experiment_config)
        elif experiment_config is not None:
            LOGGER.error(
                "Experiment config must be dictionary, but %s was provided. Config will not be logged.",
                experiment_config,
            )
            metadata = None
        else:
            metadata = None

        self._rest_client.experiments.create_experiment(
            name=name,
            dataset_name=dataset_name,
            id=id,
            metadata=metadata,
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

    def search_traces(
        self,
        project_name: Optional[str] = None,
        filter_string: Optional[str] = None,
        max_results: int = 1000,
    ) -> List[trace_public.TracePublic]:
        """
        Search for traces in the given project.

        Args:
            project_name: The name of the project to search traces in. If not provided, will search across the project name configured when the Client was created which defaults to the `Default Project`.
            filter_string: A filter string to narrow down the search. If not provided, all traces in the project will be returned up to the limit.
            max_results: The maximum number of traces to return.
        """

        page_size = 200
        traces: List[trace_public.TracePublic] = []

        filters = opik_query_language.OpikQueryLanguage(filter_string).parsed_filters

        page = 1
        while len(traces) < max_results:
            page_traces = self._rest_client.traces.get_traces_by_project(
                project_name=project_name or self._project_name,
                filters=filters,
                page=page,
                size=page_size,
            )

            if len(page_traces.content) == 0:
                break

            traces.extend(page_traces.content)
            page += 1

        return traces[:max_results]

    def search_spans(
        self,
        project_name: Optional[str] = None,
        trace_id: Optional[str] = None,
        filter_string: Optional[str] = None,
        max_results: int = 1000,
    ) -> List[span_public.SpanPublic]:
        """
        Search for spans in the given trace. This allows you to search spans based on the span input, output,
        metadata, tags, etc or based on the trace ID.

        Args:
            project_name: The name of the project to search spans in. If not provided, will search across the project name configured when the Client was created which defaults to the `Default Project`.
            trace_id: The ID of the trace to search spans in. If provided, the search will be limited to the spans in the given trace.
            filter_string: A filter string to narrow down the search.
            max_results: The maximum number of spans to return.
        """
        page_size = 200
        spans: List[span_public.SpanPublic] = []

        filters = opik_query_language.OpikQueryLanguage(filter_string).parsed_filters

        page = 1
        while len(spans) < max_results:
            page_spans = self._rest_client.spans.get_spans_by_project(
                project_name=project_name or self._project_name,
                trace_id=trace_id,
                filters=filters,
                page=page,
                size=page_size,
            )

            if len(page_spans.content) == 0:
                break

            spans.extend(page_spans.content)
            page += 1

        return spans[:max_results]

    def get_trace_content(self, id: str) -> trace_public.TracePublic:
        """
        Args:
            id (str): trace id
        Returns:
            trace_public.TracePublic: pydantic model object with all the data associated with the trace found.
            Raises an error if trace was not found.
        """
        return self._rest_client.traces.get_trace_by_id(id)

    def get_span_content(self, id: str) -> span_public.SpanPublic:
        """
        Args:
            id (str): span id
        Returns:
            span_public.SpanPublic: pydantic model object with all the data associated with the span found.
            Raises an error if span was not found.
        """
        return self._rest_client.spans.get_span_by_id(id)

    def get_project(self, id: str) -> project_public.ProjectPublic:
        """
        Fetches a project by its unique identifier.

        Parameters:
            id (str): project if (uuid).

        Returns:
            project_public.ProjectPublic: pydantic model object with all the data associated with the project found.
            Raises an error if project was not found
        """
        return self._rest_client.projects.get_project_by_id(id)


@functools.lru_cache()
def get_client_cached() -> Opik:
    client = Opik(_use_batching=True)

    return client
