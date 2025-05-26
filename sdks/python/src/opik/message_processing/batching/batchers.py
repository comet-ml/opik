from typing import Union, cast, List

from . import base_batcher, sequence_splitter
from .. import messages
from opik.rest_api.types import span_write, trace_write
from opik import jsonable_encoder, dict_utils


class CreateSpanMessageBatcher(base_batcher.BaseBatcher):
    def _create_batches_from_accumulated_messages(  # type: ignore
        self,
    ) -> List[messages.CreateSpansBatchMessage]:
        rest_spans: List[span_write.SpanWrite] = []

        for item in self._accumulated_messages:
            span_write_kwargs = item.as_payload_dict()
            cleaned_span_write_kwargs = dict_utils.remove_none_from_dict(
                span_write_kwargs
            )
            cleaned_span_write_kwargs = jsonable_encoder.encode(
                cleaned_span_write_kwargs
            )
            rest_spans.append(span_write.SpanWrite(**cleaned_span_write_kwargs))

        memory_limited_batches = sequence_splitter.split_into_batches(
            items=rest_spans,
            max_payload_size_MB=self._batch_memory_limit_mb,
        )

        batches = []
        for batch in memory_limited_batches:
            batches.append(messages.CreateSpansBatchMessage(batch=batch))

        return batches

    def add(self, message: messages.CreateSpanMessage) -> None:  # type: ignore
        return super().add(message)


class CreateTraceMessageBatcher(base_batcher.BaseBatcher):
    def _create_batches_from_accumulated_messages(  # type: ignore
        self,
    ) -> List[messages.CreateTraceBatchMessage]:
        rest_traces: List[trace_write.TraceWrite] = []

        for item in self._accumulated_messages:
            trace_write_kwargs = item.as_payload_dict()
            cleaned_trace_write_kwargs = dict_utils.remove_none_from_dict(
                trace_write_kwargs
            )
            cleaned_trace_write_kwargs = jsonable_encoder.encode(
                cleaned_trace_write_kwargs
            )
            rest_traces.append(trace_write.TraceWrite(**cleaned_trace_write_kwargs))

        memory_limited_batches = sequence_splitter.split_into_batches(
            items=rest_traces,
            max_payload_size_MB=self._batch_memory_limit_mb,
        )

        batches = []
        for batch in memory_limited_batches:
            batches.append(messages.CreateTraceBatchMessage(batch=batch))

        return batches

    def add(self, message: messages.CreateTraceMessage) -> None:  # type: ignore
        return super().add(message)


class BaseAddFeedbackScoresBatchMessageBatcher(base_batcher.BaseBatcher):
    def _create_batches_from_accumulated_messages(  # type: ignore
        self,
    ) -> List[
        Union[
            messages.AddSpanFeedbackScoresBatchMessage,
            messages.AddTraceFeedbackScoresBatchMessage,
        ]
    ]:
        return super()._create_batches_from_accumulated_messages()  # type: ignore

    def add(  # type: ignore
        self,
        message: Union[
            messages.AddSpanFeedbackScoresBatchMessage,
            messages.AddTraceFeedbackScoresBatchMessage,
        ],
    ) -> None:
        with self._lock:
            new_messages = message.batch
            n_new_messages = len(new_messages)
            n_accumulated_messages = len(self._accumulated_messages)

            if n_new_messages + n_accumulated_messages >= self._max_batch_size:
                free_space_in_accumulator = (
                    self._max_batch_size - n_accumulated_messages
                )

                messages_that_fit_in_batch = new_messages[:free_space_in_accumulator]
                messages_that_dont_fit_in_batch = new_messages[
                    free_space_in_accumulator:
                ]

                self._accumulated_messages += messages_that_fit_in_batch
                new_messages = messages_that_dont_fit_in_batch
                self.flush()

            self._accumulated_messages += new_messages


class AddSpanFeedbackScoresBatchMessageBatcher(
    BaseAddFeedbackScoresBatchMessageBatcher
):
    def _create_batches_from_accumulated_messages(  # type: ignore
        self,
    ) -> List[messages.AddSpanFeedbackScoresBatchMessage]:
        return [
            messages.AddSpanFeedbackScoresBatchMessage(
                batch=self._accumulated_messages,  # type: ignore
                supports_batching=False,
            )
        ]


class AddTraceFeedbackScoresBatchMessageBatcher(
    BaseAddFeedbackScoresBatchMessageBatcher
):
    def _create_batches_from_accumulated_messages(  # type: ignore
        self,
    ) -> List[messages.AddTraceFeedbackScoresBatchMessage]:
        return [
            messages.AddTraceFeedbackScoresBatchMessage(
                batch=self._accumulated_messages,  # type: ignore
                supports_batching=False,
            )
        ]


class GuardrailBatchMessageBatcher(base_batcher.BaseBatcher):
    def _create_batches_from_accumulated_messages(  # type: ignore
        self,
    ) -> List[messages.GuardrailBatchMessage]:
        batch = []

        for batch_message in self._accumulated_messages:
            batch_of_message_item = cast(
                messages.GuardrailBatchMessage, batch_message
            ).batch
            batch.extend(batch_of_message_item)

        return [messages.GuardrailBatchMessage(batch=batch, supports_batching=False)]  # type: ignore

    def add(self, message: messages.GuardrailBatchMessage) -> None:  # type: ignore
        return super().add(message)
