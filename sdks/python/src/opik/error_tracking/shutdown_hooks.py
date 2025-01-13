import atexit
import sys
import sentry_sdk
from typing import Optional, Type, Any
from types import TracebackType

import opik.exceptions
from opik.rest_api.core import api_error


def register_flush_hook() -> None:
    client = sentry_sdk.Hub.current.client
    if client is not None:
        atexit.register(client.flush, timeout=2.0)


def register_exception_hook() -> None:
    original_exception_hook = sys.excepthook

    def exception_hook(
        exception_type: Type[BaseException],
        exception_value: BaseException,
        traceback: Optional[TracebackType],
    ) -> Any:
        client = sentry_sdk.Hub.current.client
        if client is None:
            return original_exception_hook(exception_type, exception_value, traceback)

        is_opik_related = False

        if isinstance(
            exception_value, (opik.exceptions.OpikException, api_error.ApiError)
        ):
            is_opik_related = True

        if is_opik_related:
            sentry_sdk.capture_exception(error=exception_value)

        return original_exception_hook(exception_type, exception_value, traceback)

    sys.excepthook = exception_hook
