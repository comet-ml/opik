import httpcore
import functools
import contextlib

from typing import Iterator, Callable


@contextlib.contextmanager
def async_http_connections_expire_immediately() -> Iterator[None]:
    """
    This patching addresses the issue of httpx.AsyncClient not working
    correctly when it's used by multiple event loops.

    The connection from connection pool created with one event loop can be tried to be used
    by the request processed via another event loop. Asyncio doesn't support
    that and the RuntimeError is raised.

    So, this context manager patches AsyncHTTPConnection class in a way that all of the
    async connections expire immediately and the runtime error is not possible.

    Related issues:
    https://github.com/comet-ml/opik/issues/1132
    https://github.com/encode/httpx/discussions/2959

    TODO: this function might probably require extra logic for handling the cases
    when there is already existing async connection pool with opened connections, but it is
    out of scope for now.
    """
    try:
        original = httpcore.AsyncHTTPConnection.__init__

        def AsyncHTTPConnection__init__wrapper() -> Callable:
            @functools.wraps(original)
            def wrapped(*args, **kwargs):  # type: ignore
                kwargs["keepalive_expiry"] = 0
                return original(*args, **kwargs)

            return wrapped

        httpcore.AsyncHTTPConnection.__init__ = AsyncHTTPConnection__init__wrapper()
        yield
    finally:
        httpcore.AsyncHTTPConnection.__init__ = original
