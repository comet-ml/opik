"""Captures stdout/stderr per-job and streams to the backend asynchronously."""

import asyncio
import io
import logging
import sys
import typing

from .context import get_current_job_id
from ..rest_api.client import OpikApi
from ..rest_api.types.local_runner_log_entry import LocalRunnerLogEntry

LOGGER = logging.getLogger(__name__)

_FLUSH_INTERVAL_SECONDS = 0.5


class _CaptureStream(io.TextIOBase):
    """Intercepts writes, captures per-job log entries, forwards to the inner stream."""

    encoding: str = "utf-8"

    def __init__(
        self,
        stream: typing.TextIO,
        stream_name: str,
        loop: asyncio.AbstractEventLoop,
        queue: asyncio.Queue,
    ) -> None:
        self._stream = stream
        self._stream_name = stream_name
        self._loop = loop
        self._queue = queue
        self.encoding = getattr(stream, "encoding", "utf-8")

    def write(self, s: str) -> int:
        if s.strip():
            job_id = get_current_job_id()
            if job_id is not None:
                entry = LocalRunnerLogEntry(stream=self._stream_name, text=s)
                try:
                    self._loop.call_soon_threadsafe(
                        self._queue.put_nowait, (job_id, entry)
                    )
                except RuntimeError:
                    pass
        return self._stream.write(s)

    def flush(self) -> None:
        self._stream.flush()

    def isatty(self) -> bool:
        return self._stream.isatty()

    def fileno(self) -> int:
        return self._stream.fileno()


class LogStreamer:
    def __init__(self, api: OpikApi, loop: asyncio.AbstractEventLoop) -> None:
        self._api = api
        self._loop = loop
        self._queue: asyncio.Queue[typing.Tuple[str, LocalRunnerLogEntry]] = (
            asyncio.Queue()
        )
        self._task: typing.Optional[asyncio.Task] = None

    def install(self) -> None:
        sys.stdout = _CaptureStream(sys.stdout, "stdout", self._loop, self._queue)  # type: ignore[assignment]
        sys.stderr = _CaptureStream(sys.stderr, "stderr", self._loop, self._queue)  # type: ignore[assignment]

    def start(self) -> None:
        self._task = self._loop.create_task(self._run())

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass

    async def _run(self) -> None:
        pending: typing.Dict[str, typing.List[LocalRunnerLogEntry]] = {}

        while True:
            try:
                job_id, entry = await asyncio.wait_for(
                    self._queue.get(), timeout=_FLUSH_INTERVAL_SECONDS
                )
                pending.setdefault(job_id, []).append(entry)

                if self._queue.empty():
                    await self._drain_all(pending)
            except asyncio.TimeoutError:
                await self._drain_all(pending)
            except asyncio.CancelledError:
                await self._drain_all(pending)
                return

    async def _drain_all(
        self, pending: typing.Dict[str, typing.List[LocalRunnerLogEntry]]
    ) -> None:
        for job_id in list(pending):
            entries = pending.pop(job_id)
            if entries:
                await self._send_batch(job_id, entries)

    async def _send_batch(
        self, job_id: str, entries: typing.List[LocalRunnerLogEntry]
    ) -> None:
        try:
            await self._loop.run_in_executor(
                None,
                lambda: self._api.runners.append_job_logs(
                    job_id=job_id, request=entries
                ),
            )
        except Exception:
            LOGGER.debug("Failed to send logs for job %s", job_id, exc_info=True)
