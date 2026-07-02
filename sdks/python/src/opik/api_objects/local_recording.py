import contextlib
from typing import Iterator, List
from typing import Optional

from . import opik_client
from .. import exceptions
from ..message_processing.emulation import local_emulator_message_processor, models
from ..message_processing.processors import message_processors_chain


class _LocalRecordingHandle:
    """Lightweight handle that exposes locally recorded data.

    Accessors flush the client to ensure all in-flight messages are processed
    before reading the in-memory models.
    """

    def __init__(
        self,
        client: opik_client.Opik,
        local_processor: local_emulator_message_processor.LocalEmulatorMessageProcessor,
    ) -> None:
        self._client = client
        self._local = local_processor

    @property
    def span_trees(self) -> List[models.SpanModel]:
        self._client.flush()
        return self._local.span_trees

    @property
    def trace_trees(self) -> List[models.TraceModel]:
        self._client.flush()
        return self._local.trace_trees


@contextlib.contextmanager
def record_traces_locally(
    client: Optional[opik_client.Opik] = None,
) -> Iterator[_LocalRecordingHandle]:
    """Enable local recording of traces/spans within the context.

    Args:
        client: Optional Opik client to use for recording. If not provided, the default session client will be used.
    Usage:
        with opik.record_traces_locally() as storage:
            # your code that creates traces/spans
            spans = storage.span_trees

    Yields a handle with `span_trees` and `trace_trees` properties that flush
    the client before reading, ensuring all events are captured.

    Note:
        The local emulator is connection-scoped, so clients sharing a connection
        share it. If another operation that activates the emulator (e.g. a
        test-suite ``evaluate()``) runs concurrently on the same connection, its
        traces may also appear in this handle. Consumers that need a specific
        trace/span should look it up by id rather than assume the handle holds
        only their context's data.
    """
    if client is None:
        client = opik_client.get_global_client()

    chain = client.__internal_api__message_processor__
    local = message_processors_chain.get_local_emulator_message_processor(chain)
    if local is None:
        # Should not happen given the default chain, but guard just in case
        raise RuntimeError("Local emulator message processor not available")

    # Only one recording context per emulator. Under connection-resource
    # sharing the emulator is connection-scoped (it captures everything logged
    # on this connection), so a second recording on the same connection — even
    # from another client sharing it — is refused. This slot is independent of
    # evaluate()'s ref-counted activation, so a concurrent evaluation does not
    # spuriously block recording.
    if not local.begin_recording_context():
        raise exceptions.LocalRecordingAlreadyActive(
            "record_traces_locally() is already active on this connection; nested usage is not allowed."
        )

    message_processors_chain.toggle_local_emulator_message_processor(
        active=True, chain=chain, reset=True
    )
    handle = _LocalRecordingHandle(client=client, local_processor=local)
    try:
        yield handle
    finally:
        client.flush()
        message_processors_chain.toggle_local_emulator_message_processor(
            active=False, chain=chain, reset=True
        )
        local.end_recording_context()
