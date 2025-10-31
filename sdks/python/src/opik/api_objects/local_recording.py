import contextlib
from typing import Iterator, List

from . import opik_client
from ..message_processing import message_processors_chain
from ..message_processing.emulation import local_emulator_message_processor, models


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
def record_traces_locally() -> Iterator[_LocalRecordingHandle]:
    """Enable local recording of traces/spans within the context.

    Usage:
        with opik.record_traces_locally() as storage:
            # your code that creates traces/spans
            spans = storage.span_trees

    Yields a handle with `span_trees` and `trace_trees` properties that flush
    the client before reading, ensuring all events are captured.
    """
    client = opik_client.get_client_cached()

    # Disallow nested/local concurrent recordings in the same process
    existing_local = message_processors_chain.get_local_emulator_message_processor(
        chain=client._message_processor
    )
    if existing_local is not None and existing_local.is_active():
        raise RuntimeError(
            "record_traces_locally() is already active in the current context; nested usage is not allowed."
        )

    message_processors_chain.toggle_local_emulator_message_processor(
        active=True, chain=client._message_processor, reset=True
    )
    local = message_processors_chain.get_local_emulator_message_processor(
        chain=client._message_processor
    )
    if local is None:
        # Should not happen given the default chain, but guard just in case
        raise RuntimeError("Local emulator message processor not available")

    handle = _LocalRecordingHandle(client=client, local_processor=local)
    try:
        yield handle
    finally:
        client.flush()
        message_processors_chain.toggle_local_emulator_message_processor(
            active=False, chain=client._message_processor, reset=True
        )
