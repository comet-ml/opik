"""Bridge poll loop — runs in the supervisor, polls for bridge commands, dispatches to handlers."""

import logging
import random
import threading
import time
from concurrent.futures import Future, ThreadPoolExecutor, as_completed
from typing import Any, Dict, List, Optional, Tuple

from ..rest_api.core.api_error import ApiError
from ..rest_api.core.request_options import RequestOptions
from ..rest_api.types.bridge_command_item import BridgeCommandItem
from .bridge_handlers import BridgeCommandHandler, CommandError

LOGGER = logging.getLogger(__name__)

_POLL_TIMEOUT_SECONDS = 45
_MAX_WORKERS = 10
_REPORT_MAX_RETRIES = 3
_REPORT_BACKOFF_BASE = 1.0
_DEFAULT_COMMAND_TIMEOUT = 30.0
_MIN_COMMAND_TIMEOUT = 1.0
_MAX_COMMAND_TIMEOUT = 300.0
_MAX_BATCH_SIZE = 20


class BridgePollLoop:
    def __init__(
        self,
        api: Any,
        runner_id: str,
        handlers: Dict[str, BridgeCommandHandler],
        shutdown_event: threading.Event,
    ) -> None:
        self._api = api
        self._runner_id = runner_id
        self._handlers = handlers
        self._shutdown_event = shutdown_event

    def run(self) -> None:
        backoff = 1.0
        poll_failures = 0

        pool = ThreadPoolExecutor(max_workers=_MAX_WORKERS, thread_name_prefix="bridge-exec")
        try:
            while not self._shutdown_event.is_set():
                try:
                    batch = self._poll()
                    poll_failures = 0
                    backoff = 1.0
                except ApiError as e:
                    if e.status_code == 410:
                        LOGGER.info("Runner evicted (410), stopping bridge loop")
                        self._shutdown_event.set()
                        return
                    poll_failures += 1
                    if poll_failures == 1:
                        LOGGER.warning("Bridge poll error (API %s). Retrying...", e.status_code)
                    else:
                        LOGGER.debug("Bridge poll error (API %s)", e.status_code, exc_info=True)
                    self._backoff_wait(backoff)
                    backoff = min(backoff * 2, 30.0)
                    continue
                except Exception:
                    poll_failures += 1
                    if poll_failures == 1:
                        LOGGER.warning("Bridge poll error. Retrying...", exc_info=True)
                    else:
                        LOGGER.debug("Bridge poll error", exc_info=True)
                    self._backoff_wait(backoff)
                    backoff = min(backoff * 2, 30.0)
                    continue

                if not batch:
                    continue

                futures: Dict[Future, BridgeCommandItem] = {}
                for cmd in batch:
                    future = pool.submit(self._execute_and_report, cmd)
                    futures[future] = cmd

                for future in as_completed(futures):
                    if self._shutdown_event.is_set():
                        break
                    try:
                        future.result()
                    except Exception:
                        LOGGER.error("Unexpected error in execute_and_report", exc_info=True)
        finally:
            pool.shutdown(wait=True, cancel_futures=True)

    def _poll(self) -> List[BridgeCommandItem]:
        resp = self._api.runners.next_bridge_commands(
            self._runner_id,
            max_commands=10,
            request_options=RequestOptions(timeout_in_seconds=_POLL_TIMEOUT_SECONDS),
        )
        return (resp.commands or [])[:_MAX_BATCH_SIZE]

    def _execute_and_report(self, cmd: BridgeCommandItem) -> None:
        status, result, error, duration_ms = self._execute_command(cmd)
        self._report_result(cmd.command_id or "", status, result, error, duration_ms)

    def _execute_command(
        self, cmd: BridgeCommandItem
    ) -> Tuple[str, Optional[Dict], Optional[Dict], Optional[int]]:
        command_type = cmd.type or ""
        command_id = cmd.command_id or ""
        args = dict(cmd.args) if cmd.args else {}
        raw_timeout = cmd.timeout_seconds if cmd.timeout_seconds is not None else _DEFAULT_COMMAND_TIMEOUT
        timeout = max(_MIN_COMMAND_TIMEOUT, min(float(raw_timeout), _MAX_COMMAND_TIMEOUT))

        handler = self._handlers.get(command_type)
        if handler is None:
            return "failed", None, {"code": "unknown_type", "message": f"Unknown command type: {command_type}"}, None

        start = time.monotonic()
        try:
            result = handler.execute(args, timeout)
            duration_ms = int((time.monotonic() - start) * 1000)
            return "completed", result, None, duration_ms
        except CommandError as e:
            duration_ms = int((time.monotonic() - start) * 1000)
            return "failed", None, {"code": e.code, "message": e.message}, duration_ms
        except Exception as e:
            duration_ms = int((time.monotonic() - start) * 1000)
            LOGGER.error("Handler error for command %s: %s", command_id, e, exc_info=True)
            return "failed", None, {"code": "internal", "message": "Internal error"}, duration_ms

    def _report_result(
        self,
        command_id: str,
        status: str,
        result: Any,
        error: Any,
        duration_ms: Any,
    ) -> None:
        for attempt in range(_REPORT_MAX_RETRIES):
            try:
                self._api.runners.report_bridge_result(
                    self._runner_id,
                    command_id,
                    status=status,
                    result=result,
                    error=error,
                    duration_ms=duration_ms,
                )
                return
            except ApiError as e:
                if e.status_code == 409:
                    LOGGER.debug("Duplicate result report for %s, ignoring", command_id)
                    return
                if attempt < _REPORT_MAX_RETRIES - 1:
                    wait = _REPORT_BACKOFF_BASE * (2 ** attempt)
                    LOGGER.debug("Report failed for %s (attempt %d), retrying in %.1fs", command_id, attempt + 1, wait)
                    self._shutdown_event.wait(wait)
                else:
                    LOGGER.error("Failed to report result for command %s after %d attempts", command_id, _REPORT_MAX_RETRIES)
            except Exception:
                if attempt < _REPORT_MAX_RETRIES - 1:
                    wait = _REPORT_BACKOFF_BASE * (2 ** attempt)
                    LOGGER.debug("Report failed for %s (attempt %d), retrying in %.1fs", command_id, attempt + 1, wait)
                    self._shutdown_event.wait(wait)
                else:
                    LOGGER.error("Failed to report result for command %s after %d attempts", command_id, _REPORT_MAX_RETRIES, exc_info=True)

    def _backoff_wait(self, backoff: float) -> None:
        wait = backoff * (0.5 + random.random() * 0.5)
        self._shutdown_event.wait(wait)
