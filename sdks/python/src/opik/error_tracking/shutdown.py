import atexit

import sentry_sdk


def register_flush() -> None:
    client = sentry_sdk.Hub.current.client
    if client is not None:
        atexit.register(client.flush, timeout=2.0)
