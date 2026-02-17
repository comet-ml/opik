import logging
from typing import Callable, Dict, Type, Any

import httpx
import pydantic
import tenacity

from opik import dict_utils, exceptions, logging_messages
from opik.file_upload import base_upload_manager, types as upload_types
from opik.file_upload.s3_multipart_upload import s3_upload_error
from opik.rate_limit import rate_limit
from opik.rest_api import client as rest_api_client, core as rest_api_core
from opik.rest_api.types import (
    feedback_score_batch_item,
    feedback_score_batch_item_thread,
    guardrail,
    experiment_item,
)

from . import message_processors
from .. import encoder_helpers, messages
from ..replay import replay_manager, db_manager


LOGGER = logging.getLogger(__name__)


MessageProcessingHandler = Callable[[messages.BaseMessage], None]


class OpikMessageProcessor(message_processors.BaseMessageProcessor):
    def __init__(
        self,
        rest_client: rest_api_client.OpikApi,
        file_upload_manager: base_upload_manager.BaseFileUploadManager,
        fallback_replay_manager: replay_manager.ReplayManager,
        batch_memory_limit_mb: int = 50,
        active: bool = True,
    ):
        self._rest_client = rest_client
        self._file_uploader = file_upload_manager
        self._batch_memory_limit_mb = batch_memory_limit_mb
        self._is_active = active
        self._replay_manager = fallback_replay_manager

        self._handlers: Dict[Type, MessageProcessingHandler] = {
            messages.CreateSpanMessage: self._process_create_span_message,  # type: ignore
            messages.CreateTraceMessage: self._process_create_trace_message,  # type: ignore
            messages.UpdateSpanMessage: self._process_update_span_message,  # type: ignore
            messages.UpdateTraceMessage: self._process_update_trace_message,  # type: ignore
            messages.AddTraceFeedbackScoresBatchMessage: self._process_add_trace_feedback_scores_batch_message,  # type: ignore
            messages.AddSpanFeedbackScoresBatchMessage: self._process_add_span_feedback_scores_batch_message,  # type: ignore
            messages.AddThreadsFeedbackScoresBatchMessage: self._process_add_threads_feedback_scores_batch_message,  # type: ignore
            messages.CreateSpansBatchMessage: self._process_create_spans_batch_message,  # type: ignore
            messages.CreateTraceBatchMessage: self._process_create_traces_batch_message,  # type: ignore
            messages.GuardrailBatchMessage: self._process_guardrail_batch_message,  # type: ignore
            messages.CreateExperimentItemsBatchMessage: self._process_create_experiment_items_batch_message,  # type: ignore
            messages.CreateAttachmentMessage: self._process_create_attachment,  # type: ignore
            messages.AttachmentSupportingMessage: self._noop_handler,  # type: ignore
        }

    def is_active(self) -> bool:
        return self._is_active

    def process(self, message: messages.BaseMessage) -> None:
        if not self.is_active():
            return

        message_type = type(message)
        handler = self._handlers.get(message_type)
        if handler is None:
            LOGGER.debug("Unknown type of message - %s", message_type.__name__)
            return

        # register a message with the replay manager
        if self._replay_manager.has_server_connection:
            self._replay_manager.register_message(message)
        else:
            # register a message as failed and skip sending it to the backend
            self._replay_manager.register_message(
                message, status=db_manager.MessageStatus.failed
            )
            return

        if isinstance(message, messages.CreateAttachmentMessage):
            # it would be unregistered by callback when the upload is finished
            should_unregister_message = False
        else:
            should_unregister_message = True
        try:
            handler(message)

        except rest_api_core.ApiError as exception:
            if exception.status_code == 409:
                # sometimes a retry mechanism works in a way that it sends the same request 2 times.
                # if the backend rejects the second request, we don't want users to see an error.
                self._replay_manager.unregister_message(message.message_id)  # type: ignore
                return
            elif exception.status_code == 429:
                if exception.headers is not None:
                    rate_limiter = rate_limit.parse_rate_limit(exception.headers)
                    if rate_limiter is not None:
                        raise exceptions.OpikCloudRequestsRateLimited(
                            headers=exception.headers,
                            retry_after=rate_limiter.retry_after(),
                        )

            error_tracking_extra = _generate_error_tracking_extra(exception, message)
            LOGGER.error(
                logging_messages.FAILED_TO_PROCESS_MESSAGE_IN_BACKGROUND_STREAMER,
                message_type.__name__,
                str(exception),
                extra={"error_tracking_extra": error_tracking_extra},
            )
        except tenacity.RetryError as retry_error:
            cause = retry_error.last_attempt.exception()
            error_tracking_extra = _generate_error_tracking_extra(cause, message)
            LOGGER.error(
                logging_messages.FAILED_TO_PROCESS_MESSAGE_IN_BACKGROUND_STREAMER,
                message_type.__name__,
                f"{cause.__class__.__name__} - {cause}",
                extra={"error_tracking_extra": error_tracking_extra},
            )
            LOGGER.warning(logging_messages.MAKE_SURE_OPIK_IS_CONFIGURED_CORRECTLY)
        except pydantic.ValidationError as validation_error:
            error_tracking_extra = _generate_error_tracking_extra(
                validation_error, message
            )
            LOGGER.error(
                "Failed to process message: '%s' due to input data validation error:\n%s\n",
                message_type.__name__,
                validation_error,
                exc_info=True,
                extra={"error_tracking_extra": error_tracking_extra},
            )
        except (httpx.ConnectError, httpx.TimeoutException) as ex:
            should_unregister_message = False
            LOGGER.warning(
                "Failed to process message: '%s' due to connection error. Will be retried later when connection is restored.",
                message_type.__name__,
            )
            # mark a message as failed with replay manager due to a connection error
            self._replay_manager.message_sent_failed(
                message.message_id,  # type: ignore
                failure_reason=str(ex),
            )
        except Exception as exception:
            error_tracking_extra = _generate_error_tracking_extra(exception, message)
            LOGGER.error(
                logging_messages.FAILED_TO_PROCESS_MESSAGE_IN_BACKGROUND_STREAMER,
                message_type.__name__,
                str(exception),
                exc_info=True,
                extra={"error_tracking_extra": error_tracking_extra},
            )
            LOGGER.warning(logging_messages.MAKE_SURE_OPIK_IS_CONFIGURED_CORRECTLY)

        # unregister a message from the reply manager because it is delivered or other error occurred
        if should_unregister_message:
            self._replay_manager.unregister_message(message.message_id)  # type: ignore

    def _process_create_span_message(
        self,
        message: messages.CreateSpanMessage,
    ) -> None:
        create_span_kwargs = message.as_payload_dict()
        cleaned_create_span_kwargs = dict_utils.remove_none_from_dict(
            create_span_kwargs
        )
        cleaned_create_span_kwargs = encoder_helpers.encode_and_anonymize(
            cleaned_create_span_kwargs,
            fields_to_anonymize=message.fields_to_anonymize(),
            object_type="span",
        )

        LOGGER.debug("Create span request: %s", cleaned_create_span_kwargs)
        self._rest_client.spans.create_span(**cleaned_create_span_kwargs)

    def _process_create_trace_message(
        self,
        message: messages.CreateTraceMessage,
    ) -> None:
        create_trace_kwargs = message.as_payload_dict()
        cleaned_create_trace_kwargs = dict_utils.remove_none_from_dict(
            create_trace_kwargs
        )
        cleaned_create_trace_kwargs = encoder_helpers.encode_and_anonymize(
            cleaned_create_trace_kwargs,
            fields_to_anonymize=message.fields_to_anonymize(),
            object_type="trace",
        )

        LOGGER.debug("Create trace request: %s", cleaned_create_trace_kwargs)
        self._rest_client.traces.create_trace(**cleaned_create_trace_kwargs)

    def _process_update_span_message(
        self,
        message: messages.UpdateSpanMessage,
    ) -> None:
        update_span_kwargs = message.as_payload_dict()

        cleaned_update_span_kwargs = dict_utils.remove_none_from_dict(
            update_span_kwargs
        )
        cleaned_update_span_kwargs = encoder_helpers.encode_and_anonymize(
            cleaned_update_span_kwargs,
            fields_to_anonymize=message.fields_to_anonymize(),
            object_type="span",
        )

        LOGGER.debug("Update span request: %s", cleaned_update_span_kwargs)
        self._rest_client.spans.update_span(**cleaned_update_span_kwargs)

    def _process_update_trace_message(
        self,
        message: messages.UpdateTraceMessage,
    ) -> None:
        update_trace_kwargs = message.as_payload_dict()

        cleaned_update_trace_kwargs = dict_utils.remove_none_from_dict(
            update_trace_kwargs
        )
        cleaned_update_trace_kwargs = encoder_helpers.encode_and_anonymize(
            cleaned_update_trace_kwargs,
            fields_to_anonymize=message.fields_to_anonymize(),
            object_type="trace",
        )

        LOGGER.debug("Update trace request: %s", cleaned_update_trace_kwargs)
        self._rest_client.traces.update_trace(**cleaned_update_trace_kwargs)
        LOGGER.debug("Sent trace %s", message.trace_id)

    def _process_add_span_feedback_scores_batch_message(
        self,
        message: messages.AddSpanFeedbackScoresBatchMessage,
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
        self,
        message: messages.AddTraceFeedbackScoresBatchMessage,
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

    def _process_add_threads_feedback_scores_batch_message(
        self,
        message: messages.AddThreadsFeedbackScoresBatchMessage,
    ) -> None:
        scores = [
            feedback_score_batch_item_thread.FeedbackScoreBatchItemThread(
                **score_message.as_payload_dict()
            )
            for score_message in message.batch
        ]

        try:
            LOGGER.debug("Add threads feedbacks scores request of size %d", len(scores))
            self._rest_client.traces.score_batch_of_threads(
                scores=scores,
            )
            LOGGER.debug(
                "Sent batch of threads feedbacks scores of size %d", len(scores)
            )
        except rest_api_core.ApiError as exception:
            # In the case of AddThreadsFeedbackScoresBatchMessage, the backend will reject the request
            # if thread is not closed which can happen if the user is unaware of this fact.
            # Thus, we display the warning message.
            if exception.status_code == 409:
                LOGGER.warning(
                    "Threads feedbacks scores batch was rejected by the backend, reason: '%s'",
                    exception.body,
                )
            # propagate further to be handled in a unified error handler
            raise exception

    def _process_create_spans_batch_message(
        self, message: messages.CreateSpansBatchMessage
    ) -> None:
        LOGGER.debug("Create spans batch request of size %d", len(message.batch))
        self._rest_client.spans.create_spans(spans=message.batch)
        LOGGER.debug("Sent spans batch of size %d", len(message.batch))

    def _process_create_traces_batch_message(
        self, message: messages.CreateTraceBatchMessage
    ) -> None:
        LOGGER.debug("Create trace batch request of size %d", len(message.batch))
        self._rest_client.traces.create_traces(traces=message.batch)
        LOGGER.debug("Sent trace batch of size %d", len(message.batch))

    def _process_guardrail_batch_message(
        self,
        message: messages.GuardrailBatchMessage,
    ) -> None:
        batch = []

        for message_item in message.batch:
            guardrail_batch_item_message = guardrail.Guardrail(**message_item.__dict__)
            batch.append(guardrail_batch_item_message)

        self._rest_client.guardrails.create_guardrails(guardrails=batch)

    def _process_create_experiment_items_batch_message(
        self,
        message: messages.CreateExperimentItemsBatchMessage,
    ) -> None:
        experiment_items_batch = [
            experiment_item.ExperimentItem(
                id=item.id,
                experiment_id=item.experiment_id,
                dataset_item_id=item.dataset_item_id,
                trace_id=item.trace_id,
                project_name=item.project_name,
            )
            for item in message.batch
        ]

        LOGGER.debug(
            "Create experiment items batch request of size %d",
            len(experiment_items_batch),
        )
        self._rest_client.experiments.create_experiment_items(
            experiment_items=experiment_items_batch
        )
        LOGGER.debug(
            "Sent experiment items batch of size %d", len(experiment_items_batch)
        )

    def _process_create_attachment(
        self, message: messages.CreateAttachmentMessage
    ) -> None:
        LOGGER.debug("Processing create attachment message")
        self._file_uploader.upload(
            message,
            on_upload_failed=self._on_upload_failed_callback(message.message_id),
            on_upload_success=self._on_upload_success_callback(message.message_id),
        )
        LOGGER.debug("Uploaded attachment with %s", message.file_name)

    def _on_upload_failed_callback(
        self, message_id: int
    ) -> upload_types.OnUploadFailureCallback:
        def _callback(error: Exception) -> None:
            if (
                isinstance(error, httpx.ConnectError)
                or isinstance(error, httpx.TimeoutException)
                or (
                    isinstance(error, s3_upload_error.S3UploadError)
                    and error.connection_error
                )
            ):
                self._replay_manager.message_sent_failed(
                    message_id, failure_reason=str(error)
                )

        return _callback

    def _on_upload_success_callback(
        self, message_id: int
    ) -> upload_types.OnUploadSuccessCallback:
        def _callback() -> None:
            self._replay_manager.unregister_message(message_id)

        return _callback

    def _noop_handler(self, message: messages.BaseMessage) -> None:
        # just ignore the message
        pass


def _generate_error_tracking_extra(
    exception: Exception, message: messages.BaseMessage
) -> Dict[str, Any]:
    result: Dict[str, Any] = {"exception": exception}

    if isinstance(exception, rest_api_core.ApiError):
        fingerprint = [
            type(message).__name__,
            type(exception).__name__,
            str(exception.status_code),
        ]
        result["fingerprint"] = fingerprint
        result["status_code"] = exception.status_code

    return result
