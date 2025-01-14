import abc
import logging
from typing import Callable, Dict, Type, List

from opik import logging_messages
from . import messages
from ..jsonable_encoder import jsonable_encoder
from .. import dict_utils
from ..rest_api.types import feedback_score_batch_item, trace_write
from ..rest_api.types import span_write
from ..rest_api import core as rest_api_core
from ..rest_api import client as rest_api_client

from .batching import sequence_splitter

LOGGER = logging.getLogger(__name__)

BATCH_MEMORY_LIMIT_MB = 50


class BaseMessageProcessor(abc.ABC):
    @abc.abstractmethod
    def process(self, message: messages.BaseMessage) -> None:
        pass


class MessageSender(BaseMessageProcessor):
    def __init__(self, rest_client: rest_api_client.OpikApi):
        self._rest_client = rest_client

        self._handlers: Dict[Type, Callable[[messages.BaseMessage], None]] = {
            messages.CreateSpanMessage: self._process_create_span_message,  # type: ignore
            messages.CreateTraceMessage: self._process_create_trace_message,  # type: ignore
            messages.UpdateSpanMessage: self._process_update_span_message,  # type: ignore
            messages.UpdateTraceMessage: self._process_update_trace_message,  # type: ignore
            messages.AddTraceFeedbackScoresBatchMessage: self._process_add_trace_feedback_scores_batch_message,  # type: ignore
            messages.AddSpanFeedbackScoresBatchMessage: self._process_add_span_feedback_scores_batch_message,  # type: ignore
            messages.CreateSpansBatchMessage: self._process_create_span_batch_message,  # type: ignore
            messages.CreateTraceBatchMessage: self._process_create_trace_batch_message,  # type: ignore
        }

    def process(self, message: messages.BaseMessage) -> None:
        message_type = type(message)
        handler = self._handlers.get(message_type)
        if handler is None:
            LOGGER.debug("Unknown type of message - %s", message_type.__name__)
            return

        try:
            handler(message)
        except Exception as exception:
            if (
                isinstance(exception, rest_api_core.ApiError)
                and exception.status_code == 409
            ):
                # sometimes retry mechanism works in a way that it sends the same request 2 times.
                # second request is rejected by the backend, we don't want users to an error.
                return

            LOGGER.error(
                logging_messages.FAILED_TO_PROCESS_MESSAGE_IN_BACKGROUND_STREAMER,
                message_type.__name__,
                message,
                str(exception),
                exc_info=True,
            )

    def _process_create_span_message(self, message: messages.CreateSpanMessage) -> None:
        create_span_kwargs = message.as_payload_dict()
        cleaned_create_span_kwargs = dict_utils.remove_none_from_dict(
            create_span_kwargs
        )
        cleaned_create_span_kwargs = jsonable_encoder(cleaned_create_span_kwargs)
        LOGGER.debug("Create span request: %s", cleaned_create_span_kwargs)
        self._rest_client.spans.create_span(**cleaned_create_span_kwargs)

    def _process_create_trace_message(
        self, message: messages.CreateTraceMessage
    ) -> None:
        create_trace_kwargs = message.as_payload_dict()
        cleaned_create_trace_kwargs = dict_utils.remove_none_from_dict(
            create_trace_kwargs
        )
        cleaned_create_trace_kwargs = jsonable_encoder(cleaned_create_trace_kwargs)
        LOGGER.debug("Create trace request: %s", cleaned_create_trace_kwargs)
        self._rest_client.traces.create_trace(**cleaned_create_trace_kwargs)

    def _process_update_span_message(self, message: messages.UpdateSpanMessage) -> None:
        update_span_kwargs = {
            "id": message.span_id,
            "parent_span_id": message.parent_span_id,
            "project_name": message.project_name,
            "trace_id": message.trace_id,
            "end_time": message.end_time,
            "input": message.input,
            "output": message.output,
            "metadata": message.metadata,
            "tags": message.tags,
            "usage": message.usage,
            "model": message.model,
            "provider": message.provider,
        }

        cleaned_update_span_kwargs = dict_utils.remove_none_from_dict(
            update_span_kwargs
        )
        cleaned_update_span_kwargs = jsonable_encoder(cleaned_update_span_kwargs)
        LOGGER.debug("Update span request: %s", cleaned_update_span_kwargs)
        self._rest_client.spans.update_span(**cleaned_update_span_kwargs)

    def _process_update_trace_message(
        self, message: messages.UpdateTraceMessage
    ) -> None:
        update_trace_kwargs = {
            "id": message.trace_id,
            "project_name": message.project_name,
            "end_time": message.end_time,
            "input": message.input,
            "output": message.output,
            "metadata": message.metadata,
            "tags": message.tags,
        }

        cleaned_update_trace_kwargs = dict_utils.remove_none_from_dict(
            update_trace_kwargs
        )
        cleaned_update_trace_kwargs = jsonable_encoder(cleaned_update_trace_kwargs)
        LOGGER.debug("Update trace request: %s", cleaned_update_trace_kwargs)
        self._rest_client.traces.update_trace(**cleaned_update_trace_kwargs)
        LOGGER.debug("Sent trace %s", message.trace_id)

    def _process_add_span_feedback_scores_batch_message(
        self, message: messages.AddSpanFeedbackScoresBatchMessage
    ) -> None:
        scores = [
            feedback_score_batch_item.FeedbackScoreBatchItem(**score_message.__dict__)
            for score_message in message.batch
        ]

        LOGGER.debug("Add spans feedbacks scores request of size: %d", len(scores))

        self._rest_client.spans.score_batch_of_spans(
            scores=scores,
        )
        LOGGER.debug("Sent batch of spans feedback scores %d", len(scores))

    def _process_add_trace_feedback_scores_batch_message(
        self, message: messages.AddTraceFeedbackScoresBatchMessage
    ) -> None:
        scores = [
            feedback_score_batch_item.FeedbackScoreBatchItem(**score_message.__dict__)
            for score_message in message.batch
        ]

        LOGGER.debug("Add traces feedbacks scores request: %d", len(scores))

        self._rest_client.traces.score_batch_of_traces(
            scores=scores,
        )
        LOGGER.debug("Sent batch of traces feedbacks scores of size %d", len(scores))

    def _process_create_span_batch_message(
        self, message: messages.CreateSpansBatchMessage
    ) -> None:
        rest_spans: List[span_write.SpanWrite] = []

        for item in message.batch:
            span_write_kwargs = item.as_payload_dict()
            cleaned_span_write_kwargs = dict_utils.remove_none_from_dict(
                span_write_kwargs
            )
            cleaned_span_write_kwargs = jsonable_encoder(cleaned_span_write_kwargs)
            rest_spans.append(span_write.SpanWrite(**cleaned_span_write_kwargs))

        memory_limited_batches = sequence_splitter.split_into_batches(
            items=rest_spans,
            max_payload_size_MB=BATCH_MEMORY_LIMIT_MB,
        )

        for batch in memory_limited_batches:
            LOGGER.debug("Create spans batch request of size %d", len(batch))
            self._rest_client.spans.create_spans(spans=batch)
            LOGGER.debug("Sent spans batch of size %d", len(batch))

    def _process_create_trace_batch_message(
        self, message: messages.CreateTraceBatchMessage
    ) -> None:
        rest_traces: List[trace_write.TraceWrite] = []

        for item in message.batch:
            trace_write_kwargs = item.as_payload_dict()
            cleaned_trace_write_kwargs = dict_utils.remove_none_from_dict(
                trace_write_kwargs
            )
            cleaned_trace_write_kwargs = jsonable_encoder(cleaned_trace_write_kwargs)
            rest_traces.append(trace_write.TraceWrite(**cleaned_trace_write_kwargs))

        memory_limited_batches = sequence_splitter.split_into_batches(
            items=rest_traces,
            max_payload_size_MB=BATCH_MEMORY_LIMIT_MB,
        )

        for batch in memory_limited_batches:
            LOGGER.debug("Create trace batch request of size %d", len(batch))
            self._rest_client.traces.create_traces(traces=batch)
            LOGGER.debug("Sent trace batch of size %d", len(batch))
