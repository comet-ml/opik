from concurrent.futures import Future, CancelledError

import httpx

from . import upload_monitor
from ..rest_api import client as rest_api_client


class UploadResult:
    def __init__(
        self, future: Future, monitor: upload_monitor.FileUploadMonitor
    ) -> None:
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

    def __init__(
        self, rest_client: rest_api_client.OpikApi, httpx_client: httpx.Client
    ) -> None:
        self._httpx_client = httpx_client
