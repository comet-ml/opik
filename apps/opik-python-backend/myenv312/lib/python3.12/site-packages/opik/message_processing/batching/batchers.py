from typing import Union

from . import base_batcher
from .. import messages


class CreateSpanMessageBatcher(base_batcher.BaseBatcher):
    def _create_batch_from_accumulated_messages(
        self,
    ) -> messages.CreateSpansBatchMessage:
        return messages.CreateSpansBatchMessage(batch=self._accumulated_messages)  # type: ignore

    def add(self, message: messages.CreateSpansBatchMessage) -> None:  # type: ignore
        return super().add(message)


class CreateTraceMessageBatcher(base_batcher.BaseBatcher):
    def _create_batch_from_accumulated_messages(
        self,
    ) -> messages.CreateTraceBatchMessage:
        return messages.CreateTraceBatchMessage(batch=self._accumulated_messages)  # type: ignore

    def add(self, message: messages.CreateTraceBatchMessage) -> None:  # type: ignore
        return super().add(message)


class BaseAddFeedbackScoresBatchMessageBatcher(base_batcher.BaseBatcher):
    def _create_batch_from_accumulated_messages(  # type: ignore
        self,
    ) -> Union[
        messages.AddSpanFeedbackScoresBatchMessage,
        messages.AddTraceFeedbackScoresBatchMessage,
    ]:
        return super()._create_batch_from_accumulated_messages()  # type: ignore

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
    def _create_batch_from_accumulated_messages(
        self,
    ) -> messages.AddSpanFeedbackScoresBatchMessage:  # type: ignore
        return messages.AddSpanFeedbackScoresBatchMessage(
            batch=self._accumulated_messages,  # type: ignore
            supports_batching=False,
        )


class AddTraceFeedbackScoresBatchMessageBatcher(
    BaseAddFeedbackScoresBatchMessageBatcher
):
    def _create_batch_from_accumulated_messages(
        self,
    ) -> messages.AddTraceFeedbackScoresBatchMessage:  # type: ignore
        return messages.AddTraceFeedbackScoresBatchMessage(
            batch=self._accumulated_messages,  # type: ignore
            supports_batching=False,
        )
