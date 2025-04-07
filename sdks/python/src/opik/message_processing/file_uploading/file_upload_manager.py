import dataclasses
from concurrent.futures import Future, CancelledError
from typing import Optional

import httpx


@dataclasses.dataclass
class UploadSizeMonitor:
    total_size: Optional[int] = None
    bytes_sent: int = 0

    def reset(self) -> None:
        self.bytes_sent = 0


class UploadResult:
    def __init__(self, future: Future, monitor: UploadSizeMonitor) -> None:
        self.future = future
        self.monitor = monitor

    def ready(self) -> bool:
        """Allows to check if wrapped Future successfully finished"""
        return self.future.done()

    def successful(self) -> bool:
        """Allows to check if wrapped Future completed without raising an exception"""
        try:
            return self.future.exception() is None
        except (CancelledError, TimeoutError):
            return False


class FileUploadManager:
    """Manages file uploads in parallel."""

    def __init__(self, httpx_client: httpx.Client) -> None:
        self._httpx_client = httpx_client
