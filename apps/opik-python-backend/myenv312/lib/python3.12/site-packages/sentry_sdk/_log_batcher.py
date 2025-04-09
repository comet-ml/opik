import os
import random
import threading
from datetime import datetime, timezone
from typing import Optional, List, Callable, TYPE_CHECKING, Any

from sentry_sdk.utils import format_timestamp, safe_repr
from sentry_sdk.envelope import Envelope

if TYPE_CHECKING:
    from sentry_sdk._types import Log


class LogBatcher:
    MAX_LOGS_BEFORE_FLUSH = 100
    FLUSH_WAIT_TIME = 5.0

    def __init__(
        self,
        capture_func,  # type: Callable[[Envelope], None]
    ):
        # type: (...) -> None
        self._log_buffer = []  # type: List[Log]
        self._capture_func = capture_func
        self._running = True
        self._lock = threading.Lock()

        self._flush_event = threading.Event()  # type: threading.Event

        self._flusher = None  # type: Optional[threading.Thread]
        self._flusher_pid = None  # type: Optional[int]

    def _ensure_thread(self):
        # type: (...) -> bool
        """For forking processes we might need to restart this thread.
        This ensures that our process actually has that thread running.
        """
        if not self._running:
            return False

        pid = os.getpid()
        if self._flusher_pid == pid:
            return True

        with self._lock:
            # Recheck to make sure another thread didn't get here and start the
            # the flusher in the meantime
            if self._flusher_pid == pid:
                return True

            self._flusher_pid = pid

            self._flusher = threading.Thread(target=self._flush_loop)
            self._flusher.daemon = True

            try:
                self._flusher.start()
            except RuntimeError:
                # Unfortunately at this point the interpreter is in a state that no
                # longer allows us to spawn a thread and we have to bail.
                self._running = False
                return False

        return True

    def _flush_loop(self):
        # type: (...) -> None
        while self._running:
            self._flush_event.wait(self.FLUSH_WAIT_TIME + random.random())
            self._flush_event.clear()
            self._flush()

    def add(
        self,
        log,  # type: Log
    ):
        # type: (...) -> None
        if not self._ensure_thread() or self._flusher is None:
            return None

        with self._lock:
            self._log_buffer.append(log)
            if len(self._log_buffer) >= self.MAX_LOGS_BEFORE_FLUSH:
                self._flush_event.set()

    def kill(self):
        # type: (...) -> None
        if self._flusher is None:
            return

        self._running = False
        self._flush_event.set()
        self._flusher = None

    def flush(self):
        # type: (...) -> None
        self._flush()

    @staticmethod
    def _log_to_otel(log):
        # type: (Log) -> Any
        def format_attribute(key, val):
            # type: (str, int | float | str | bool) -> Any
            if isinstance(val, bool):
                return {"key": key, "value": {"boolValue": val}}
            if isinstance(val, int):
                return {"key": key, "value": {"intValue": str(val)}}
            if isinstance(val, float):
                return {"key": key, "value": {"doubleValue": val}}
            if isinstance(val, str):
                return {"key": key, "value": {"stringValue": val}}
            return {"key": key, "value": {"stringValue": safe_repr(val)}}

        otel_log = {
            "severityText": log["severity_text"],
            "severityNumber": log["severity_number"],
            "body": {"stringValue": log["body"]},
            "timeUnixNano": str(log["time_unix_nano"]),
            "attributes": [
                format_attribute(k, v) for (k, v) in log["attributes"].items()
            ],
        }

        if "trace_id" in log:
            otel_log["traceId"] = log["trace_id"]

        return otel_log

    def _flush(self):
        # type: (...) -> Optional[Envelope]

        envelope = Envelope(
            headers={"sent_at": format_timestamp(datetime.now(timezone.utc))}
        )
        with self._lock:
            for log in self._log_buffer:
                envelope.add_log(self._log_to_otel(log))
            self._log_buffer.clear()
        if envelope.items:
            self._capture_func(envelope)
            return envelope
        return None
