"""
Posts events to Comet's stats collector, which forwards them to Segment and on to
PostHog. The same route the Opik backend already reports through, so SDK events land
in the same pipeline as everything else Opik reports.

The endpoint takes no credentials - it exists to receive anonymous reports from
installations that have none - which is why the SDK needs no write key to use it.
One event per request; it has no batch form.
"""

import logging
from typing import Any, Dict, List

import httpx

from . import worker
from .. import environment, package_version

LOGGER = logging.getLogger(__name__)

CONNECT_TIMEOUT_SECONDS = 3
REQUEST_TIMEOUT_SECONDS = 10

LIBRARY_NAME = "opik-python-sdk"

# Statuses that mean "do not send this again", as opposed to "that attempt failed".
# The destination can retire a whole SDK, or one version of it, by answering with one
# of these - it identifies both from the `User-Agent` this client sends.
# 429 and 5xx are deliberately absent: they say try later, not stop.
REJECTED_STATUSES = frozenset({401, 403, 404, 410})


def _to_payload(event: worker.Event) -> Dict[str, Any]:
    """The shape the collector accepts, matching the backend's `BiEvent`."""
    return {
        "anonymous_id": environment.get_user_identifier(),
        "event_type": event.name,
        "event_properties": event.properties,
    }


class Sender:
    def __init__(self, url: str) -> None:
        self._url = url
        self._client = httpx.Client(
            timeout=httpx.Timeout(
                REQUEST_TIMEOUT_SECONDS, connect=CONNECT_TIMEOUT_SECONDS
            ),
            # Always verified, deliberately not honouring `check_tls_certificate`.
            # That setting is about reaching the user's own deployment - a
            # self-hosted Opik behind a self-signed certificate - and this is a
            # fixed public endpoint that has no such problem. Letting it apply here
            # would silently weaken a connection the user never pointed at.
            verify=True,
            headers={"User-Agent": f"{LIBRARY_NAME}/{package_version.VERSION}"},
        )

    def close(self) -> None:
        """Releases the connection pool. Safe to call more than once."""
        self._client.close()

    def send(self, events: List[worker.Event]) -> None:
        for event in events:
            # Per event rather than around the loop: the collector takes one event per
            # request, so letting a single failed request escape would discard every
            # event queued behind it, and they are only ever reported once.
            try:
                response = self._client.post(self._url, json=_to_payload(event))
            except Exception:
                LOGGER.debug(
                    "Failed to report analytics event %s", event.name, exc_info=True
                )
                continue

            if response.status_code in REJECTED_STATUSES:
                raise worker.ReportingRejected(
                    f"destination answered {response.status_code}"
                )

            if response.status_code >= 400:
                LOGGER.debug(
                    "Analytics event %s rejected with status %d",
                    event.name,
                    response.status_code,
                )
