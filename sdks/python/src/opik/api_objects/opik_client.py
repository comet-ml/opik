import atexit
import datetime
import functools
import logging
from typing import Any, Dict, List, Optional, TypeVar, Union, Literal, cast

import httpx

from . import (
    constants,
    dataset,
    experiment,
    optimization,
    helpers,
    opik_query_language,
    search_helpers,
    span,
    trace,
)
from .attachment import Attachment
from .attachment import client as attachment_client
from .attachment import converters as attachment_converters
from .dataset import rest_operations as dataset_rest_operations
from .experiment import experiments_client
from .experiment import helpers as experiment_helpers
from .experiment import rest_operations as experiment_rest_operations
from . import prompt as prompt_module
from .prompt import client as prompt_client
from .threads import threads_client
from .trace import migration as trace_migration, trace_client
from .. import (
    config,
    datetime_helpers,
    exceptions,
    httpx_client,
    id_helpers,
    llm_usage,
    rest_client_configurator,
    url_helpers,
)
from ..message_processing import (
    messages,
    streamer_constructors,
    message_queue,
)
from ..message_processing.batching import sequence_splitter
from ..message_processing.processors import message_processors_chain
from ..rest_api import client as rest_api_client
from ..rest_api.core.api_error import ApiError
from ..rest_api.types import (
    dataset_public,
    project_public,
    span_public,
    trace_public,
    span_filter_public,
    trace_filter_public,
)
from ..types import (
    BatchFeedbackScoreDict,
    ErrorInfoDict,
    FeedbackScoreDict,
    LLMProvider,
    SpanType,
)

LOGGER = logging.getLogger(__name__)

T = TypeVar("T")


class Opik:
    def __init__(
        self,
        project_name: Optional[str] = None,
        workspace: Optional[str] = None,
        host: Optional[str] = None,
        api_key: Optional[str] = None,
        _use_batching: bool = False,
        _show_misconfiguration_message: bool = True,
    ) -> None:
        """
        Initialize an Opik object that can be used to log traces and spans manually to Opik server.

        Args:
            project_name: The name of the project. If not provided, traces and spans will be logged to the `Default Project`.
            workspace: The name of the workspace. If not provided, `default` will be used.
            host: The host URL for the Opik server. If not provided, it will default to `https://www.comet.com/opik/api`.
            api_key: The API key for Opik. This parameter is ignored for local installations.
            _use_batching: intended for internal usage in specific conditions only.
                Enabling it is unsafe and can lead to data loss.
            _show_misconfiguration_message: intended for internal usage in specific conditions only.
                Print a warning message if the Opik server is not configured properly.
        Returns:
            None
        """

        config_ = config.get_from_user_inputs(
            project_name=project_name,
            workspace=workspace,
            url_override=host,
            api_key=api_key,
        )

        config_.check_for_known_misconfigurations(
            show_misconfiguration_message=_show_misconfiguration_message,
        )
        self._config = config_

        self._workspace: str = config_.workspace
        self._project_name: str = config_.project_name
        self._flush_timeout: Optional[int] = config_.default_flush_timeout
        self._project_name_most_recent_trace: Optional[str] = None
        self._use_batching = _use_batching

        self._initialize_streamer(
            use_batching=_use_batching,
        )
        atexit.register(self.end, timeout=self._flush_timeout)

    @property
    def config(self) -> config.OpikConfig:
        """
        Returns:
            config.OpikConfig: Read-only copy of the configuration of the Opik client.
        """
        return self._config.model_copy()

    @property
    def rest_client(self) -> rest_api_client.OpikApi:
        """
        Provides direct access to the underlying REST API client.

        WARNING: This client is not guaranteed to be backward compatible with future SDK versions.
        While it provides a convenient way to use the current REST API of Opik.
        However, it's not considered safe to heavily rely on its API as Opik's REST API contracts may change.

        Returns:
            OpikApi: The REST client used by the Opik client.
        """
        return self._rest_client

    @property
    def project_name(self) -> str:
        """
        This property retrieves the name of the project associated with the instance.
        It is a read-only property.

        Returns:
            str: The name of the project.
        """
        return self._project_name

    def _initialize_streamer(
        self,
        use_batching: bool,
    ) -> None:
        self._httpx_client = httpx_client.get(
            workspace=self._workspace,
            api_key=self._config.api_key,
            check_tls_certificate=self._config.check_tls_certificate,
            compress_json_requests=self._config.enable_json_request_compression,
        )
        self._rest_client = rest_api_client.OpikApi(
            base_url=self._config.url_override,
            httpx_client=self._httpx_client,
        )
        self._rest_client._client_wrapper._timeout = (
            httpx.USE_CLIENT_DEFAULT
        )  # See https://github.com/fern-api/fern/issues/5321
        rest_client_configurator.configure(self._rest_client)

        max_queue_size = message_queue.calculate_max_queue_size(
            maximal_queue_size=self._config.maximal_queue_size,
            batch_factor=self._config.maximal_queue_size_batch_factor,
        )

        self.__internal_api__message_processor__ = (
            message_processors_chain.create_message_processors_chain(
                rest_client=self._rest_client
            )
        )
        self._streamer = streamer_constructors.construct_online_streamer(
            n_consumers=self._config.background_workers,
            rest_client=self._rest_client,
            httpx_client=self._httpx_client,
            use_batching=use_batching,
            use_attachment_extraction=self._config.is_attachment_extraction_active,
            min_base64_embedded_attachment_size=self._config.min_base64_embedded_attachment_size,
            file_upload_worker_count=self._config.file_upload_background_workers,
            max_queue_size=max_queue_size,
            message_processor=self.__internal_api__message_processor__,
            url_override=self._config.url_override,
        )

    def _display_trace_url(self, trace_id: str, project_name: str) -> None:
        project_url = url_helpers.get_project_url_by_trace_id(
            trace_id=trace_id,
            url_override=self._config.url_override,
        )
        if (
            self._project_name_most_recent_trace is None
            or self._project_name_most_recent_trace != project_name
        ):
            LOGGER.info(
                f'Started logging traces to the "{project_name}" project at {project_url}.'
            )
            self._project_name_most_recent_trace = project_name

    def _display_created_dataset_url(self, dataset_name: str, dataset_id: str) -> None:
        dataset_url = url_helpers.get_dataset_url_by_id(
            dataset_id, self._config.url_override
        )

        LOGGER.info(f'Created a "{dataset_name}" dataset at {dataset_url}.')

    def auth_check(self) -> None:
        """
        Checks if current API key user has an access to the configured workspace and its content.
        """
        self._rest_client.check.access(
            request={}  # empty body for future backward compatibility
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
        feedback_scores: Optional[List[FeedbackScoreDict]] = None,
        project_name: Optional[str] = None,
        error_info: Optional[ErrorInfoDict] = None,
        thread_id: Optional[str] = None,
        attachments: Optional[List[Attachment]] = None,
        **ignored_kwargs: Any,
    ) -> trace.Trace:
        """
        Create and log a new trace.

        Args:
            id: The unique identifier for the trace, if not provided, a new ID will be generated. Must be a valid [UUIDv7](https://uuid7.com/) ID.
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
            error_info: The dictionary with error information (typically used when the trace function has failed).
            thread_id: Used to group multiple traces into a thread.
                The identifier is user-defined and has to be unique per project.
            attachments: The list of attachments to be uploaded to the trace.

        Returns:
            trace.Trace: The created trace object.
        """
        id = id if id is not None else id_helpers.generate_id()
        start_time = (
            start_time if start_time is not None else datetime_helpers.local_timestamp()
        )
        last_updated_at = datetime_helpers.local_timestamp()

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
            error_info=error_info,
            thread_id=thread_id,
            last_updated_at=last_updated_at,
        )
        self._streamer.put(create_trace_message)
        self._display_trace_url(trace_id=id, project_name=project_name)

        if feedback_scores is not None:
            for feedback_score in feedback_scores:
                feedback_score["id"] = id

            self.log_traces_feedback_scores(
                cast(List[BatchFeedbackScoreDict], feedback_scores), project_name
            )

        if attachments is not None:
            for attachment_data in attachments:
                self._streamer.put(
                    attachment_converters.attachment_to_message(
                        attachment_data=attachment_data,
                        entity_type="trace",
                        entity_id=id,
                        project_name=project_name,
                        url_override=self._config.url_override,
                    )
                )

        return trace.Trace(
            id=id,
            message_streamer=self._streamer,
            project_name=project_name,
            url_override=self._config.url_override,
        )

    def copy_traces(
        self,
        project_name: str,
        destination_project_name: str,
        delete_original_project: bool = False,
    ) -> None:
        """
        Copy traces from one project to another. This method will copy all traces in a source project
        to the destination project. Optionally, you can also delete these traces from the source project.

        As the traces are copied, the IDs for both traces and spans will be updated as part of the copy
        process.

        Note: This method is not optimized for large projects, if you run into any issues please raise
        an issue on GitHub. In addition, be aware that deleting traces that are linked to experiments
        will lead to inconsistencies in the UI.

        Args:
            project_name: The name of the project to copy traces from.
            destination_project_name: The name of the project to copy traces to.
            delete_original_project: Whether to delete the original project. Defaults to False.

        Returns:
            None
        """

        if not self._use_batching:
            raise exceptions.OpikException(
                "In order to use this method, you must enable batching using opik.Opik(_use_batching=True)."
            )

        traces_public = self.search_traces(project_name=project_name)
        spans_public = self.search_spans(project_name=project_name)

        trace_data = [
            trace.trace_public_to_trace_data(
                project_name=project_name, trace_public=trace_public_
            )
            for trace_public_ in traces_public
        ]
        span_data = [
            span.span_public_to_span_data(
                project_name=project_name, span_public_=span_public_
            )
            for span_public_ in spans_public
        ]

        new_trace_data, new_span_data = (
            trace_migration.prepare_traces_and_spans_for_copy(
                destination_project_name, trace_data, span_data
            )
        )

        for trace_data_ in new_trace_data:
            self.trace(**trace_data_.as_parameters)

        for span_data_ in new_span_data:
            self.span(**span_data_.as_parameters)

        if delete_original_project:
            trace_ids = [trace_.id for trace_ in trace_data]
            for batch in sequence_splitter.split_into_batches(
                trace_ids,
                max_length=constants.DELETE_TRACE_BATCH_SIZE,
            ):
                self._rest_client.traces.delete_traces(ids=batch)

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
        usage: Optional[Union[Dict[str, Any], llm_usage.OpikUsage]] = None,
        feedback_scores: Optional[List[FeedbackScoreDict]] = None,
        project_name: Optional[str] = None,
        model: Optional[str] = None,
        provider: Optional[Union[str, LLMProvider]] = None,
        error_info: Optional[ErrorInfoDict] = None,
        total_cost: Optional[float] = None,
        attachments: Optional[List[Attachment]] = None,
    ) -> span.Span:
        """
        Create and log a new span.

        Args:
            trace_id: The unique identifier for the trace. If not provided, a new ID will be generated. Must be a valid [UUIDv7](https://uuid7.com/) ID.
            id: The unique identifier for the span. If not provided, a new ID will be generated. Must be a valid [UUIDv7](https://uuid.ramsey.dev/en/stable/rfc4122/version8.html) ID.
            parent_span_id: The unique identifier for the parent span.
            name: The name of the span.
            type: The type of the span. Default is "general".
            start_time: The start time of the span. If not provided, the current local time will be used.
            end_time: The end time of the span.
            metadata: Additional metadata for the span. This can be any valid JSON serializable object.
            input: The input data for the span. This can be any valid JSON serializable object.
            output: The output data for the span. This can be any valid JSON serializable object.
            tags: Tags associated with the span.
            feedback_scores: The list of feedback score dicts associated with the span. Dicts don't require having an `id` value.
            project_name: The name of the project. If not set, the project name which was configured when the Opik instance
                was created will be used.
            usage: Usage data for the span. In order for input, output, and total tokens to be visible in the UI,
                the usage must contain OpenAI-formatted keys (they can be passed additionally to the original usage on the top level of the dict): prompt_tokens, completion_tokens, and total_tokens.
                If OpenAI-formatted keys were not found, Opik will try to calculate them automatically if the usage
                format is recognized (you can see which provider's formats are recognized in opik.LLMProvider enum), but it is not guaranteed.
            model: The name of LLM (in this case `type` parameter should be == `llm`)
            provider: The provider of LLM. You can find providers officially supported by Opik for cost tracking
                in `opik.LLMProvider` enum. If your provider is not here, please open an issue in our GitHub - https://github.com/comet-ml/opik.
                If your provider is not in the list, you can still specify it, but the cost tracking will not be available
            error_info: The dictionary with error information (typically used when the span function has failed).
            total_cost: The cost of the span in USD. This value takes priority over the cost calculated by Opik from the usage.
            attachments: The list of attachments to be uploaded to the span.

        Returns:
            span.Span: The created span object.
        """
        id = id if id is not None else id_helpers.generate_id()
        start_time = (
            start_time if start_time is not None else datetime_helpers.local_timestamp()
        )

        if project_name is None:
            project_name = self._project_name

        if trace_id is None:
            trace_id = id_helpers.generate_id()
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
                error_info=error_info,
                thread_id=None,
                last_updated_at=datetime_helpers.local_timestamp(),
            )
            self._streamer.put(create_trace_message)

        if feedback_scores is not None:
            for feedback_score in feedback_scores:
                feedback_score["id"] = id

            self.log_spans_feedback_scores(
                cast(List[BatchFeedbackScoreDict], feedback_scores), project_name
            )

        return span.span_client.create_span(
            trace_id=trace_id,
            project_name=project_name,
            url_override=self._config.url_override,
            message_streamer=self._streamer,
            span_id=id,
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
            model=model,
            provider=provider,
            error_info=error_info,
            total_cost=total_cost,
            attachments=attachments,
        )

    def update_span(
        self,
        id: str,
        trace_id: str,
        parent_span_id: Optional[str],
        project_name: str,
        end_time: Optional[datetime.datetime] = None,
        metadata: Optional[Dict[str, Any]] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        tags: Optional[List[str]] = None,
        usage: Optional[Union[Dict[str, Any], llm_usage.OpikUsage]] = None,
        model: Optional[str] = None,
        provider: Optional[Union[LLMProvider, str]] = None,
        error_info: Optional[ErrorInfoDict] = None,
        total_cost: Optional[float] = None,
        attachments: Optional[List[Attachment]] = None,
    ) -> None:
        """
        Update the attributes of an existing span.

        This method should only be used after the span has been fully created and stored.
        If called before or immediately after span creation, the update may silently fail or result in incorrect data.

        This method uses four parameters to identify the span:
            - `id`
            - `trace_id`
            - `parent_span_id`
            - `project_name`

        These parameters **must match exactly** the values used when the span was created.
        If any of them are incorrect, the update may not apply and no error will be raised.

        All other parameters are optional and will update the corresponding fields in the span.
        If a parameter is not provided, the existing value will remain unchanged.

        Args:
            id: The unique identifier for the span to update.
            trace_id: The unique identifier for the trace to which the span belongs.
            parent_span_id: The unique identifier for the parent span.
            project_name: The project name to which the span belongs.
            end_time: The new end time of the span.
            metadata: The new metadata to be associated with the span.
            input: The new input data for the span.
            output: The new output data for the span.
            tags: A new list of tags to be associated with the span.
            usage: The new usage data for the span. In order for input, output and total tokens to be visible in the UI,
                the usage must contain OpenAI-formatted keys (they can be passed additionaly to original usage on the top level of the dict):  prompt_tokens, completion_tokens and total_tokens.
                If OpenAI-formatted keys were not found, Opik will try to calculate them automatically if the usage
                format is recognized (you can see which provider's formats are recognized in opik.LLMProvider enum), but it is not guaranteed.
            model: The new name of LLM.
            provider: The new provider of LLM. You can find providers officially supported by Opik for cost tracking
                in `opik.LLMProvider` enum. If your provider is not here, please open an issue in our github - https://github.com/comet-ml/opik.
                If your provider not in the list, you can still specify it but the cost tracking will not be available
            error_info: The new dictionary with error information (typically used when the span function has failed).
            total_cost: The new cost of the span in USD. This value takes priority over the cost calculated by Opik from the usage.
            attachments: The new list of attachments to be uploaded to the span.

        Returns:
            None
        """
        span.span_client.update_span(
            id=id,
            trace_id=trace_id,
            parent_span_id=parent_span_id,
            url_override=self._config.url_override,
            message_streamer=self._streamer,
            project_name=project_name,
            end_time=end_time,
            metadata=metadata,
            input=input,
            output=output,
            tags=tags,
            usage=usage,
            model=model,
            provider=provider,
            error_info=error_info,
            total_cost=total_cost,
            attachments=attachments,
        )

    def update_trace(
        self,
        trace_id: str,
        project_name: str,
        end_time: Optional[datetime.datetime] = None,
        metadata: Optional[Dict[str, Any]] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        tags: Optional[List[Any]] = None,
        error_info: Optional[ErrorInfoDict] = None,
        thread_id: Optional[str] = None,
    ) -> None:
        """
        Update the trace attributes.

        This method should only be used after the trace has been fully created and stored.
        If called before or immediately after trace creation, the update may silently fail or result in incorrect data.

        This method uses two parameters to identify the trace:
            - `trace_id`
            - `project_name`

        These parameters **must match exactly** the values used when the trace was created.
        If any of them are incorrect, the update may not apply and no error will be raised.

        All other parameters are optional and will update the corresponding fields in the trace.
        If a parameter is not provided, the existing value will remain unchanged.

        Args:
            trace_id: The unique identifier for the trace.
            project_name: The project name to which the trace belongs.
            end_time: The end time of the trace.
            metadata: Additional metadata to be associated with the trace.
            input: The input data for the trace.
            output: The output data for the trace.
            tags: A list of tags to be associated with the trace.
            error_info: The dictionary with error information (typically used when the trace function has failed).
            thread_id: Used to group multiple traces into a thread.
                The identifier is user-defined and has to be unique per project.

        Returns:
            None
        """
        if not trace_id or not project_name:
            raise ValueError(
                "trace_id and project_name must be provided and can not be None or empty, "
                f"trace_id: {trace_id}, project_name: {project_name}"
            )

        trace_client.update_trace(
            trace_id=trace_id,
            project_name=project_name,
            message_streamer=self._streamer,
            end_time=end_time,
            metadata=metadata,
            input=input,
            output=output,
            tags=tags,
            error_info=error_info,
            thread_id=thread_id,
        )

    def log_spans_feedback_scores(
        self, scores: List[BatchFeedbackScoreDict], project_name: Optional[str] = None
    ) -> None:
        """
        Log feedback scores for spans.

        Args:
            scores (List[BatchFeedbackScoreDict]): A list of feedback score dictionaries.
                Specifying a span id via `id` key for each score is mandatory.
            project_name: The name of the project in which the spans are logged. If not set, the project name
                which was configured when the Opik instance was created will be used.
                Deprecated: use `project_name` in the feedback score dictionary that's listed in the `scores` parameter.

        Returns:
            None

        Example:
            >>> from opik import Opik
            >>> client = Opik()
            >>> # Batch logging across multiple projects
            >>> scores = [
            >>>     {"id": span1_id, "name": "accuracy", "value": 0.95, "project_name": "project-A"},
            >>>     {"id": span2_id, "name": "accuracy", "value": 0.88, "project_name": "project-B"},
            >>> ]
            >>> client.log_spans_feedback_scores(scores=scores)
        """
        score_messages = helpers.parse_feedback_score_messages(
            scores=scores,
            project_name=project_name or self.project_name,
            parsed_item_class=messages.FeedbackScoreMessage,
            logger=LOGGER,
        )
        if score_messages is None:
            LOGGER.error(
                f"No valid spans feedback scores to log from provided ones: {scores}"
            )
            return

        for batch in sequence_splitter.split_into_batches(
            score_messages,
            max_payload_size_MB=config.MAX_BATCH_SIZE_MB,
            max_length=constants.FEEDBACK_SCORES_MAX_BATCH_SIZE,
        ):
            add_span_feedback_scores_batch_message = (
                messages.AddSpanFeedbackScoresBatchMessage(batch=batch)
            )

            self._streamer.put(add_span_feedback_scores_batch_message)

    def log_traces_feedback_scores(
        self, scores: List[BatchFeedbackScoreDict], project_name: Optional[str] = None
    ) -> None:
        """
        Log feedback scores for traces.

        Args:
            scores (List[BatchFeedbackScoreDict]): A list of feedback score dictionaries.
                Specifying a trace id via `id` key for each score is mandatory.
            project_name: The name of the project in which the traces are logged. If not set, the project name
                which was configured when the Opik instance was created will be used.
                Deprecated: use `project_name` in the feedback score dictionary that's listed in the `scores` parameter.

        Returns:
            None

        Example:
            >>> from opik import Opik
            >>> client = Opik()
            >>> # Batch logging across multiple projects
            >>> scores = [
            >>>     {"id": trace1_id, "name": "accuracy", "value": 0.95, "project_name": "project-A"},
            >>>     {"id": trace2_id, "name": "accuracy", "value": 0.88, "project_name": "project-B"},
            >>> ]
            >>> client.log_traces_feedback_scores(scores=scores)
        """
        score_messages = helpers.parse_feedback_score_messages(
            scores=scores,
            project_name=project_name or self.project_name,
            parsed_item_class=messages.FeedbackScoreMessage,
            logger=LOGGER,
        )

        if score_messages is None:
            LOGGER.error(
                f"No valid traces feedback scores to log from provided ones: {scores}"
            )
            return

        for batch in sequence_splitter.split_into_batches(
            score_messages,
            max_payload_size_MB=config.MAX_BATCH_SIZE_MB,
            max_length=constants.FEEDBACK_SCORES_MAX_BATCH_SIZE,
        ):
            add_trace_feedback_scores_batch_message = (
                messages.AddTraceFeedbackScoresBatchMessage(batch=batch)
            )

            self._streamer.put(add_trace_feedback_scores_batch_message)

    def log_threads_feedback_scores(
        self, scores: List[BatchFeedbackScoreDict], project_name: Optional[str] = None
    ) -> None:
        """
        Log feedback scores for threads.

        Args:
            scores (List[BatchFeedbackScoreDict]): A list of feedback score dictionaries.
                Specifying a thread id via `id` key for each score is mandatory.
            project_name: The name of the project in which the threads are logged. If not set, the project name
                which was configured when the Opik instance was created will be used.
                Deprecated: use `project_name` in the feedback score dictionary that's listed in the `scores` parameter.

        Returns:
            None

        Example:
            >>> from opik import Opik
            >>> client = Opik()
            >>> # Batch logging across multiple projects
            >>> scores = [
            >>>     {"id": "thread_123", "name": "user_satisfaction", "value": 0.85, "project_name": "project-A"},
            >>>     {"id": "thread_456", "name": "user_satisfaction", "value": 0.92, "project_name": "project-B"},
            >>> ]
            >>> client.log_threads_feedback_scores(scores=scores)
        """
        self.get_threads_client().log_threads_feedback_scores(
            scores=scores, project_name=project_name
        )

    def delete_trace_feedback_score(self, trace_id: str, name: str) -> None:
        """
        Deletes a feedback score associated with a specific trace.

        Args:
            trace_id:
                The unique identifier of the trace for which the feedback score needs to be deleted.
            name: str
                The name associated with the feedback score that should be deleted.

        Returns:
            None
        """
        self._rest_client.traces.delete_trace_feedback_score(
            id=trace_id,
            name=name,
        )

    def delete_span_feedback_score(self, span_id: str, name: str) -> None:
        """
        Deletes a feedback score associated with a specific span.

        Args:
            span_id:
                The unique identifier of the trace for which the feedback score needs to be deleted.
            name: str
                The name associated with the feedback score that should be deleted.

        Returns:
            None
        """
        self._rest_client.spans.delete_span_feedback_score(
            id=span_id,
            name=name,
        )

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

    def get_datasets(
        self,
        max_results: int = 100,
        sync_items: bool = True,
    ) -> List[dataset.Dataset]:
        """
        Returns all datasets up to the specified limit.

        Args:
            max_results: The maximum number of datasets to return.
            sync_items: Whether to sync the hashes of the dataset items. This is used to deduplicate items when fetching the dataset but it can be an expensive operation.

        Returns:
            List[dataset.Dataset]: A list of dataset objects that match the filter string.
        """
        datasets = dataset_rest_operations.get_datasets(
            self._rest_client, max_results, sync_items
        )

        return datasets

    def get_dataset_experiments(
        self,
        dataset_name: str,
        max_results: int = 100,
    ) -> List[experiment.Experiment]:
        """
        Returns all experiments up to the specified limit.

        Args:
            dataset_name: The name of the dataset
            max_results: The maximum number of experiments to return.

        Returns:
            List[experiment.Experiment]: A list of experiment objects.
        """
        dataset_id = dataset_rest_operations.get_dataset_id(
            self._rest_client, dataset_name
        )

        experiments_client = self.get_experiments_client()
        experiments = dataset_rest_operations.get_dataset_experiments(
            rest_client=self._rest_client,
            dataset_id=dataset_id,
            max_results=max_results,
            streamer=self._streamer,
            experiments_client=experiments_client,
        )

        return experiments

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

        self._display_created_dataset_url(dataset_name=name, dataset_id=result.id)

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
        prompt: Optional[prompt_module.base_prompt.BasePrompt] = None,
        prompts: Optional[List[prompt_module.base_prompt.BasePrompt]] = None,
        type: Literal["regular", "trial", "mini-batch"] = "regular",
        optimization_id: Optional[str] = None,
    ) -> experiment.Experiment:
        """
        Creates a new experiment using the given dataset name and optional parameters.

        Args:
            dataset_name: The name of the dataset to associate with the experiment.
            name: The optional name for the experiment. If None, a generated name will be used.
            experiment_config: Optional experiment configuration parameters. Must be a dictionary if provided.
            prompt: Prompt object to associate with the experiment. Deprecated, use `prompts` argument instead.
            prompts: List of Prompt objects to associate with the experiment.
            type: The type of the experiment. Can be "regular", "trial", or "mini-batch".
                Defaults to "regular". "trial" and "mini-batch" are only relevant for prompt optimization experiments.
            optimization_id: Optional ID of the optimization associated with the experiment.

        Returns:
            experiment.Experiment: The newly created experiment object.
        """
        id = id_helpers.generate_id()

        checked_prompts = experiment_helpers.handle_prompt_args(
            prompt=prompt,
            prompts=prompts,
        )

        metadata, prompt_versions = experiment.build_metadata_and_prompt_versions(
            experiment_config=experiment_config,
            prompts=checked_prompts,
        )

        self._rest_client.experiments.create_experiment(
            name=name,
            dataset_name=dataset_name,
            id=id,
            metadata=metadata,
            prompt_versions=prompt_versions,
            type=type,
            optimization_id=optimization_id,
        )

        experiment_ = experiment.Experiment(
            id=id,
            name=name,
            dataset_name=dataset_name,
            rest_client=self._rest_client,
            streamer=self._streamer,
            experiments_client=self.get_experiments_client(),
            prompts=checked_prompts,
        )

        return experiment_

    def update_experiment(
        self,
        id: str,
        name: Optional[str] = None,
        experiment_config: Optional[Dict[str, Any]] = None,
    ) -> None:
        """
        Update an experiment's name and/or configuration.

        Args:
            id: The experiment ID.
            name: The new name for the experiment. If None, the name will not be updated.
            experiment_config: The new configuration for the experiment. If None, the configuration will not be updated.

        Raises:
            ValueError: if id is None or empty, or if both name and experiment_config are None
        """
        if not id:
            raise ValueError(
                f"id must be provided and can not be None or empty, id: {id}"
            )

        if name is None and experiment_config is None:
            raise ValueError(
                "At least one of 'name' or 'experiment_config' must be provided"
            )

        # Only include parameters that are provided to avoid clearing fields
        request_params: Dict[str, Any] = {}
        if name is not None:
            request_params["name"] = name
        if experiment_config is not None:
            request_params["metadata"] = experiment_config

        self._rest_client.experiments.update_experiment(id, **request_params)

    def get_experiment_by_name(self, name: str) -> experiment.Experiment:
        """
        Returns an existing experiment by its name.

        Args:
            name: The name of the experiment.

        Returns:
            experiment.Experiment: the API object for an existing experiment.
        """
        LOGGER.warning(
            "Deprecated, use `get_experiments_by_name` or `get_experiment_by_id` instead."
        )
        experiment_public = experiment_rest_operations.get_experiment_data_by_name(
            rest_client=self._rest_client, name=name
        )

        return experiment.Experiment(
            id=experiment_public.id,
            name=experiment_public.name,
            dataset_name=experiment_public.dataset_name,
            rest_client=self._rest_client,
            streamer=self._streamer,
            experiments_client=self.get_experiments_client(),
        )

    def get_experiments_by_name(self, name: str) -> List[experiment.Experiment]:
        """
        Returns a list of existing experiments containing the given string in their name.
        Search is case-insensitive.

        Args:
            name: The string to search for in the experiment names.

        Returns:
            List[experiment.Experiment]: List of existing experiments.
        """
        experiments_public = experiment_rest_operations.get_experiments_data_by_name(
            rest_client=self._rest_client, name=name
        )
        result = []

        for public_experiment in experiments_public:
            experiment_ = experiment.Experiment(
                id=public_experiment.id,
                name=public_experiment.name,
                dataset_name=public_experiment.dataset_name,
                rest_client=self._rest_client,
                streamer=self._streamer,
                experiments_client=self.get_experiments_client(),
            )
            result.append(experiment_)

        return result

    def get_experiment_by_id(self, id: str) -> experiment.Experiment:
        """
        Returns an existing experiment by its id.

        Args:
            id: The id of the experiment.

        Returns:
            experiment.Experiment: the API object for an existing experiment.
        """
        try:
            experiment_public = self._rest_client.experiments.get_experiment_by_id(
                id=id
            )
        except ApiError as exception:
            if exception.status_code == 404:
                raise exceptions.ExperimentNotFound(
                    f"Experiment with the id {id} not found."
                ) from exception
            raise

        return experiment.Experiment(
            id=experiment_public.id,
            name=experiment_public.name,
            dataset_name=experiment_public.dataset_name,
            rest_client=self._rest_client,
            streamer=self._streamer,
            experiments_client=self.get_experiments_client(),
        )

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

    def flush(self, timeout: Optional[int] = None) -> bool:
        """
        Flush the streamer to ensure all messages are sent.

        Args:
            timeout (Optional[int]): The timeout for flushing the streamer. Once the timeout is reached, the flush method will return regardless of whether all messages have been sent.

        Returns:
            True if all messages have been sent within specified timeout, False otherwise.
        """
        timeout = timeout if timeout is not None else self._flush_timeout
        return self._streamer.flush(timeout)

    def search_traces(
        self,
        project_name: Optional[str] = None,
        filter_string: Optional[str] = None,
        max_results: int = 1000,
        truncate: bool = True,
        wait_for_at_least: Optional[int] = None,
        wait_for_timeout: int = httpx_client.READ_TIMEOUT_SECONDS,
    ) -> List[trace_public.TracePublic]:
        """
        Search for traces in the given project. Optionally, you can wait for at least a certain number of traces
        to be found before returning within the specified timeout. If wait_for_at_least number of traces are not found
        within the specified timeout, an exception will be raised.

        Args:
            project_name: The name of the project to search traces in. If not provided, will search across the project name configured when the Client was created which defaults to the `Default Project`.
            filter_string: A filter string to narrow down the search using Opik Query Language (OQL).
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

                Supported operators by column:
                - `id`, `name`, `created_by`, `thread_id`, `type`, `model`, `provider`: =, !=, contains, not_contains, starts_with, ends_with, >, <
                - `status`: =, contains, not_contains
                - `start_time`, `end_time`: =, >, <, >=, <=
                - `input`, `output`: =, contains, not_contains
                - `metadata`: =, contains, >, <
                - `feedback_scores`: =, >, <, >=, <=, is_empty, is_not_empty
                - `tags`: contains (only)
                - `usage.total_tokens`, `usage.prompt_tokens`, `usage.completion_tokens`, `duration`, `number_of_messages`, `total_estimated_cost`: =, !=, >, <, >=, <=

                Examples:
                - `start_time >= "2024-01-01T00:00:00Z"` - Filter by start date
                - `start_time > "2024-01-01T00:00:00Z" AND start_time < "2024-02-01T00:00:00Z"` - Date range
                - `input contains "question"` - Filter by input content
                - `usage.total_tokens > 1000` - Filter by token usage
                - `feedback_scores.accuracy > 0.8` - Filter by feedback score
                - `feedback_scores.my_metric is_empty` - Filter traces with empty feedback score
                - `feedback_scores.my_metric is_not_empty` - Filter traces with non-empty feedback score
                - `tags contains "production"` - Filter by tag
                - `metadata.model = "gpt-4"` - Filter by metadata field
                - `thread_id = "thread_123"` - Filter by thread ID

                If not provided, all traces in the project will be returned up to the limit.
            max_results: The maximum number of traces to return.
            truncate: Whether to truncate image data stored in input, output, or metadata
            wait_for_at_least: The minimum number of traces to wait for before returning.
            wait_for_timeout: The timeout for waiting for traces.

        Raises:
            exceptions.SearchTimeoutError if wait_for_at_least traces are not found within the specified timeout.
        """
        filters_ = helpers.parse_filter_expressions(
            filter_string, parsed_item_class=trace_filter_public.TraceFilterPublic
        )

        search_functor = functools.partial(
            search_helpers.search_traces_with_filters,
            rest_client=self._rest_client,
            project_name=project_name or self._project_name,
            filters=filters_,
            max_results=max_results,
            truncate=truncate,
        )

        if wait_for_at_least is None:
            return search_functor()

        # do synchronization with backend if wait_for_at_least is provided until a specific number of traces are found
        result = search_helpers.search_and_wait_for_done(
            search_functor=search_functor,
            wait_for_at_least=wait_for_at_least,
            wait_for_timeout=wait_for_timeout,
            sleep_time=5,
        )
        if len(result) < wait_for_at_least:
            raise exceptions.SearchTimeoutError(
                f"Timeout after {wait_for_timeout} seconds: expected {wait_for_at_least} traces, but only {len(result)} were found."
            )

        return result

    def search_spans(
        self,
        project_name: Optional[str] = None,
        trace_id: Optional[str] = None,
        filter_string: Optional[str] = None,
        max_results: int = 1000,
        truncate: bool = True,
        wait_for_at_least: Optional[int] = None,
        wait_for_timeout: int = httpx_client.READ_TIMEOUT_SECONDS,
    ) -> List[span_public.SpanPublic]:
        """
        Search for spans in the given trace. This allows you to search spans based on the span input, output,
        metadata, tags, etc. or based on the trace ID. Also, you can wait for at least a certain number of spans
        to be found before returning within the specified timeout. If wait_for_at_least number of spans are not found
        within the specified timeout, an exception will be raised.

        Args:
            project_name: The name of the project to search spans in. If not provided, will search across the project name configured when the Client was created which defaults to the `Default Project`.
            trace_id: The ID of the trace to search spans in. If provided, the search will be limited to the spans in the given trace.
            filter_string: A filter string to narrow down the search using Opik Query Language (OQL).
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

                Supported operators by column:
                - `id`, `name`, `created_by`, `thread_id`, `type`, `model`, `provider`: =, !=, contains, not_contains, starts_with, ends_with, >, <
                - `status`: =, contains, not_contains
                - `start_time`, `end_time`: =, >, <, >=, <=
                - `input`, `output`: =, contains, not_contains
                - `metadata`: =, contains, >, <
                - `feedback_scores`: =, >, <, >=, <=, is_empty, is_not_empty
                - `tags`: contains (only)
                - `usage.total_tokens`, `usage.prompt_tokens`, `usage.completion_tokens`, `duration`, `number_of_messages`, `total_estimated_cost`: =, !=, >, <, >=, <=

                Examples:
                - `start_time >= "2024-01-01T00:00:00Z"` - Filter by start date
                - `start_time > "2024-01-01T00:00:00Z" AND start_time < "2024-02-01T00:00:00Z"` - Date range
                - `input contains "question"` - Filter by input content
                - `usage.total_tokens > 1000` - Filter by token usage
                - `feedback_scores.accuracy > 0.8` - Filter by feedback score
                - `feedback_scores.my_metric is_empty` - Filter spans with empty feedback score
                - `feedback_scores.my_metric is_not_empty` - Filter spans with non-empty feedback score
                - `tags contains "production"` - Filter by tag
                - `metadata.model = "gpt-4"` - Filter by metadata field
                - `thread_id = "thread_123"` - Filter by thread ID

                If not provided, all spans in the project/trace will be returned up to the limit.
            max_results: The maximum number of spans to return.
            truncate: Whether to truncate image data stored in input, output, or metadata
            wait_for_at_least: The minimum number of spans to wait for before returning.
            wait_for_timeout: The timeout for waiting for spans.

        Raises:
            exceptions.SearchTimeoutError if wait_for_at_least spans are not found within the specified timeout.
        """
        filters = helpers.parse_filter_expressions(
            filter_string, parsed_item_class=span_filter_public.SpanFilterPublic
        )

        search_functor = functools.partial(
            search_helpers.search_spans_with_filters,
            rest_client=self._rest_client,
            project_name=project_name or self._project_name,
            trace_id=trace_id,
            filters=filters,
            max_results=max_results,
            truncate=truncate,
        )

        if wait_for_at_least is None:
            return search_functor()

        # do synchronization with backend if wait_for_at_least is provided until a specific number of spans are found
        result = search_helpers.search_and_wait_for_done(
            search_functor=search_functor,
            wait_for_at_least=wait_for_at_least,
            wait_for_timeout=wait_for_timeout,
            sleep_time=5,
        )
        if len(result) < wait_for_at_least:
            raise exceptions.SearchTimeoutError(
                f"Timeout after {wait_for_timeout} seconds: expected {wait_for_at_least} spans, but only {len(result)} were found."
            )

        return result

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
            id (str): project id (uuid).

        Returns:
            project_public.ProjectPublic: pydantic model object with all the data associated with the project found.
            Raises an error if project was not found
        """
        return self._rest_client.projects.get_project_by_id(id)

    def get_project_url(self, project_name: Optional[str] = None) -> str:
        """
        Returns a URL to the project in the current workspace.
        This method does not make any requests or perform any checks (e.g. that the project exists).
        It only builds a URL string based on the data provided.

        Parameters:
            project_name (str): project name to return URL for.
                If not provided, a default project name for the current Opik instance will be used.

        Returns:
            str: URL
        """

        dereferenced_workspace = self._workspace
        if dereferenced_workspace == config.OPIK_WORKSPACE_DEFAULT_NAME:
            dereferenced_workspace = (
                self._rest_client.check.get_workspace_name().workspace_name
            )

        project_name = project_name or self._project_name

        return url_helpers.get_project_url_by_workspace(
            workspace=dereferenced_workspace, project_name=project_name
        )

    def get_threads_client(self) -> threads_client.ThreadsClient:
        """
        Creates and provides an instance of the ``ThreadsClient`` tied to the current context.

        The ``ThreadsClient`` can be used to interact with the threads API to manage and interact with conversational threads.

        Returns:
            ThreadsClient: An instance of ``threads_client.ThreadsClient`` initialized
            with the current context.
        """
        return threads_client.ThreadsClient(self)

    def get_attachment_client(self) -> attachment_client.AttachmentClient:
        """
        Creates and provides an instance of the ``AttachmentClient`` tied to the current context.

        The ``AttachmentClient`` can be used to interact with the attachments API to retrieve
        attachment lists, download attachments, and upload attachments for traces and spans.

        Returns:
            AttachmentClient: An instance of ``attachment.client.AttachmentClient``
        """
        return attachment_client.AttachmentClient(
            rest_client=self._rest_client,
            url_override=self._config.url_override,
            workspace_name=self._workspace,
            rest_httpx_client=self._httpx_client,
        )

    def create_prompt(
        self,
        name: str,
        prompt: str,
        metadata: Optional[Dict[str, Any]] = None,
        type: prompt_module.PromptType = prompt_module.PromptType.MUSTACHE,
    ) -> prompt_module.Prompt:
        """
        Creates a new text prompt with the given name and template.
        If a text prompt with the same name already exists, it will create a new version of the existing prompt if the templates differ.

        Parameters:
            name: The name of the prompt.
            prompt: The template content of the prompt.
            metadata: Optional metadata to be included in the prompt.
            type: The template type (MUSTACHE or JINJA2).

        Returns:
            A Prompt object containing details of the created or retrieved prompt.

        Raises:
            PromptTemplateStructureMismatch: If a chat prompt with the same name already exists (template structure is immutable).
            ApiError: If there is an error during the creation of the prompt.
        """
        prompt_client_ = prompt_client.PromptClient(self._rest_client)
        prompt_version = prompt_client_.create_prompt(
            name=name, prompt=prompt, metadata=metadata, type=type
        )
        return prompt_module.Prompt.from_fern_prompt_version(name, prompt_version)

    def create_chat_prompt(
        self,
        name: str,
        messages: List[Dict[str, Any]],
        metadata: Optional[Dict[str, Any]] = None,
        type: prompt_module.PromptType = prompt_module.PromptType.MUSTACHE,
    ) -> prompt_module.ChatPrompt:
        """
        Creates a new chat prompt with the given name and message templates.
        If a chat prompt with the same name already exists, it will create a new version if the messages differ.

        Parameters:
            name: The name of the chat prompt.
            messages: List of message dictionaries with 'role' and 'content' fields.
            metadata: Optional metadata to be included in the prompt.
            type: The template type (MUSTACHE or JINJA2).

        Returns:
            A ChatPrompt object containing details of the created or retrieved chat prompt.

        Raises:
            PromptTemplateStructureMismatch: If a text prompt with the same name already exists (template structure is immutable).
            ApiError: If there is an error during the creation of the prompt.
        """
        return prompt_module.ChatPrompt(
            name=name, messages=messages, metadata=metadata, type=type
        )

    def get_prompt(
        self,
        name: str,
        commit: Optional[str] = None,
    ) -> Optional[prompt_module.Prompt]:
        """
        Retrieve a text prompt by name and optional commit version.

        This method only returns text prompts.

        Parameters:
            name: The name of the prompt.
            commit: An optional commit version of the prompt. If not provided, the latest version is retrieved.

        Returns:
            Prompt: The details of the specified text prompt, or None if not found.

        Raises:
            PromptTemplateStructureMismatch: If the prompt exists but is a chat prompt (template structure mismatch).
        """
        prompt_client_ = prompt_client.PromptClient(self._rest_client)
        fern_prompt_version = prompt_client_.get_prompt(
            name=name, commit=commit, raise_if_not_template_structure="text"
        )

        if fern_prompt_version is None:
            return None

        return prompt_module.Prompt.from_fern_prompt_version(name, fern_prompt_version)

    def get_chat_prompt(
        self,
        name: str,
        commit: Optional[str] = None,
    ) -> Optional[prompt_module.ChatPrompt]:
        """
        Retrieve a chat prompt by name and optional commit version.

        This method only returns chat prompts.

        Parameters:
            name: The name of the prompt.
            commit: An optional commit version of the prompt. If not provided, the latest version is retrieved.

        Returns:
            ChatPrompt: The details of the specified chat prompt, or None if not found.

        Raises:
            PromptTemplateStructureMismatch: If the prompt exists but is a text prompt (template structure mismatch).
        """
        prompt_client_ = prompt_client.PromptClient(self._rest_client)
        fern_prompt_version = prompt_client_.get_prompt(
            name=name, commit=commit, raise_if_not_template_structure="chat"
        )

        if fern_prompt_version is None:
            return None

        return prompt_module.ChatPrompt.from_fern_prompt_version(
            name, fern_prompt_version
        )

    def get_prompt_history(self, name: str) -> List[prompt_module.Prompt]:
        """
        Retrieve all text prompt versions history for a given prompt name.

        Parameters:
            name: The name of the prompt.

        Returns:
            List[Prompt]: A list of text Prompt instances for the given name, or an empty list if not found.

        Raises:
            PromptTemplateStructureMismatch: If the prompt exists but is a chat prompt (template structure mismatch).
        """
        prompt_client_ = prompt_client.PromptClient(self._rest_client)

        # First, validate that this is a text prompt by trying to get the latest version
        # Let PromptTemplateStructureMismatch exception propagate - this is a hard error
        latest_version = prompt_client_.get_prompt(
            name=name, raise_if_not_template_structure="text"
        )

        if latest_version is None:
            return []

        # Now get all versions (we know it's a text prompt)
        fern_prompt_versions = prompt_client_.get_all_prompt_versions(name=name)

        result = [
            prompt_module.Prompt.from_fern_prompt_version(name, version)
            for version in fern_prompt_versions
        ]
        return result

    def get_chat_prompt_history(self, name: str) -> List[prompt_module.ChatPrompt]:
        """
        Retrieve all chat prompt versions history for a given prompt name.

        Parameters:
            name: The name of the prompt.

        Returns:
            List[ChatPrompt]: A list of ChatPrompt instances for the given name, or an empty list if not found.

        Raises:
            PromptTemplateStructureMismatch: If the prompt exists but is a text prompt (template structure mismatch).
        """
        prompt_client_ = prompt_client.PromptClient(self._rest_client)

        # First, validate that this is a chat prompt by trying to get the latest version
        # Let PromptTemplateStructureMismatch exception propagate - this is a hard error
        latest_version = prompt_client_.get_prompt(
            name=name, raise_if_not_template_structure="chat"
        )

        if latest_version is None:
            return []

        # Now get all versions (we know it's a chat prompt)
        fern_prompt_versions = prompt_client_.get_all_prompt_versions(name=name)

        result = [
            prompt_module.ChatPrompt.from_fern_prompt_version(name, version)
            for version in fern_prompt_versions
        ]
        return result

    def get_all_prompts(self, name: str) -> List[prompt_module.Prompt]:
        """
        DEPRECATED: Please use Opik.get_prompt_history() instead.
        Retrieve all the prompt versions history for a given prompt name.

        Parameters:
            name: The name of the prompt.

        Returns:
            List[prompt_module.Prompt]: A list of Prompt instances for the given name.
        """
        LOGGER.warning(
            "Opik.get_all_prompts() is deprecated. Please use Opik.get_prompt_history() instead."
        )
        return self.get_prompt_history(name)

    def search_prompts(
        self, filter_string: Optional[str] = None
    ) -> List[Union[prompt_module.Prompt, prompt_module.ChatPrompt]]:
        """
        Retrieve the latest prompt versions (both string and chat prompts) for the given search parameters.

        Parameters:
            filter_string: A filter string to narrow down the search using Opik Query Language (OQL).
                The format is: "<COLUMN> <OPERATOR> <VALUE> [AND <COLUMN> <OPERATOR> <VALUE>]*"

                Supported columns include:
                - `id`, `name`: String fields
                - `tags`: List field (use "contains" operator only)
                - `created_by`: String field
                - `template_structure`: String field ("string" or "chat")

                Supported operators by column:
                - `id`: =, !=, contains, not_contains, starts_with, ends_with, >, <
                - `name`: =, !=, contains, not_contains, starts_with, ends_with, >, <
                - `created_by`: =, !=, contains, not_contains, starts_with, ends_with, >, <
                - `template_structure`: =, !=
                - `tags`: contains (only)

                Examples:
                - `tags contains "alpha"` - Filter by tag
                - `tags contains "alpha" AND tags contains "beta"` - Filter by multiple tags
                - `name contains "summary"` - Filter by name substring
                - `created_by = "user@example.com"` - Filter by creator
                - `id starts_with "prompt_"` - Filter by ID prefix
                - `template_structure = "text"` - Only text prompts
                - `template_structure = "chat"` - Only chat prompts

                If not provided, all prompts (both text and chat) will be returned.

        Returns:
            List[Union[Prompt, ChatPrompt]]: A list of Prompt and/or ChatPrompt instances found.
        """
        oql = opik_query_language.OpikQueryLanguage(filter_string or "")
        parsed_filters = oql.get_filter_expressions()

        prompt_client_ = prompt_client.PromptClient(self._rest_client)
        search_results = prompt_client_.search_prompts(parsed_filters=parsed_filters)

        # Convert to Prompt or ChatPrompt objects based on template_structure
        prompts: List[Union[prompt_module.Prompt, prompt_module.ChatPrompt]] = []
        for result in search_results:
            if result.template_structure == "chat":
                prompts.append(
                    prompt_module.ChatPrompt.from_fern_prompt_version(
                        result.name, result.prompt_version_detail
                    )
                )
            else:
                prompts.append(
                    prompt_module.Prompt.from_fern_prompt_version(
                        result.name, result.prompt_version_detail
                    )
                )

        return prompts

    def create_optimization(
        self,
        dataset_name: str,
        objective_name: str,
        name: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
        optimization_id: Optional[str] = None,
    ) -> optimization.Optimization:
        id = optimization_id or id_helpers.generate_id()

        self._rest_client.optimizations.create_optimization(
            id=id,
            name=name,
            dataset_name=dataset_name,
            objective_name=objective_name,
            status="running",
            metadata=metadata,
        )

        optimization_client = optimization.Optimization(
            id=id, rest_client=self._rest_client
        )
        return optimization_client

    def delete_optimizations(self, ids: List[str]) -> None:
        self._rest_client.optimizations.delete_optimizations_by_id(ids=ids)

    def get_optimization_by_id(self, id: str) -> optimization.Optimization:
        _ = self._rest_client.optimizations.get_optimization_by_id(id)
        return optimization.Optimization(id=id, rest_client=self._rest_client)

    def get_experiments_client(self) -> experiments_client.ExperimentsClient:
        """
        Retrieves an instance of `ExperimentsClient`.

        Returns:
            An instance of the ExperimentsClient initialized with a cached REST client.
        """
        return experiments_client.ExperimentsClient(self._rest_client)


@functools.lru_cache()
def get_client_cached() -> Opik:
    client = Opik(_use_batching=True)

    return client
