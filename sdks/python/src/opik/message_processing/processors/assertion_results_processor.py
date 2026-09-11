"""
Migration-period write path for assertion results (OPIK-6054 / OPIK-6048).

Tries PUT /v1/private/assertion-results first; on 404/405 falls back to
PUT /v1/private/traces/feedback-scores with category_name="suite_assertion"
(the pre-OPIK-6054 piggyback) and remembers the choice for the rest of the
process so the round-trip cost is paid only once.

This class goes away when the legacy feedback-scores write path is removed.
"""

import logging

from opik.rest_api import client as rest_api_client, core as rest_api_core
from opik.rest_api.types import (
    assertion_result_batch_item,
    feedback_score_batch_item,
)

from .. import messages

LOGGER = logging.getLogger(__name__)


class AssertionResultsMessageProcessor:
    def __init__(self, rest_client: rest_api_client.OpikApi) -> None:
        self._rest_client = rest_client
        # Default True: assume the BE exposes the new endpoint. Flipped to
        # False on the first 404/405 to skip the round-trip on subsequent
        # batches in this process.
        self._use_assertion_results_endpoint = True

    def process(self, message: messages.AddAssertionResultsBatchMessage) -> None:
        if self._use_assertion_results_endpoint:
            try:
                self._send_via_assertion_results_endpoint(message)
                return
            except rest_api_core.ApiError as exception:
                if exception.status_code in (404, 405):
                    LOGGER.warning(
                        "assertion-results endpoint unavailable on this "
                        "backend (status=%d); falling back to feedback-scores "
                        "for the rest of this session.",
                        exception.status_code,
                    )
                    self._use_assertion_results_endpoint = False
                else:
                    raise

        self._send_via_feedback_scores(message)

    def _send_via_assertion_results_endpoint(
        self,
        message: messages.AddAssertionResultsBatchMessage,
    ) -> None:
        items = [
            assertion_result_batch_item.AssertionResultBatchItem(
                entity_id=item.entity_id,
                project_name=item.project_name,
                name=item.name,
                status=item.status,
                reason=item.reason,
                source=item.source,
            )
            for item in message.batch
        ]

        LOGGER.debug("Add assertion results request of size: %d", len(items))

        self._rest_client.assertion_results.store_assertions_batch(
            entity_type=message.entity_type,
            assertion_results=items,
        )
        LOGGER.debug("Sent batch of assertion results of size %d", len(items))

    def _send_via_feedback_scores(
        self,
        message: messages.AddAssertionResultsBatchMessage,
    ) -> None:
        scores = [
            feedback_score_batch_item.FeedbackScoreBatchItem(
                id=item.entity_id,
                project_name=item.project_name,
                name=item.name,
                value=1.0 if item.status == "passed" else 0.0,
                category_name="suite_assertion",
                reason=item.reason,
                source=item.source,
            )
            for item in message.batch
        ]
        LOGGER.debug(
            "Falling back: writing %d assertion results as trace feedback scores",
            len(scores),
        )
        self._rest_client.traces.score_batch_of_traces(scores=scores)
