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


def _to_payload(event: worker.Event) -> Dict[str, Any]:
    """The shape the collector accepts, matching the backend's `BiEvent`."""
    return {
        "anonymous_id": environment.get_user_identifier(),
        "event_type": event.name,
        "event_properties": event.properties,
    }


class Sender:
    def __init__(self, url: str, check_tls_certificate: bool) -> None:
        self._url = url
        self._client = httpx.Client(
            timeout=httpx.Timeout(
                REQUEST_TIMEOUT_SECONDS, connect=CONNECT_TIMEOUT_SECONDS
            ),
            verify=check_tls_certificate,
            headers={"User-Agent": f"{LIBRARY_NAME}/{package_version.VERSION}"},
        )

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

            if response.status_code >= 400:
                LOGGER.debug(
                    "Analytics event %s rejected with status %d",
                    event.name,
                    response.status_code,
                )
