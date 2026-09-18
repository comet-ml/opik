import httpcore
import functools
import contextlib
import threading

from typing import Iterator, Callable, Optional


_patch_lock = threading.Lock()
_patch_depth = 0
_original_init: Optional[Callable] = None


def _keepalive_expiry_zero(original: Callable) -> Callable:
    @functools.wraps(original)
    def wrapped(*args, **kwargs):  # type: ignore
        kwargs["keepalive_expiry"] = 0
        return original(*args, **kwargs)

    wrapped._opik_keepalive_patch = True  # type: ignore
    return wrapped


def _is_installed() -> bool:
    return (
        getattr(httpcore.AsyncHTTPConnection.__init__, "_opik_keepalive_patch", False)
        is True
    )


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

    The patch is installed on a class, so runs that overlap share it, and they do
    not necessarily leave in the order they entered. Activation is therefore
    ref-counted: the first to enter installs it and only the last to leave removes
    it. Otherwise the first one out either disarms the patch for the runs still in
    progress or restores the wrapper it inherited, which leaves every async
    connection of the host application short-lived for the rest of the process.

    Related issues:
    https://github.com/comet-ml/opik/issues/1132
    https://github.com/encode/httpx/discussions/2959

    TODO: this function might probably require extra logic for handling the cases
    when there is already existing async connection pool with opened connections, but it is
    out of scope for now.
    """
    global _patch_depth, _original_init

    with _patch_lock:
        if _patch_depth == 0:
            _original_init = httpcore.AsyncHTTPConnection.__init__
        # The counter is only a count of our own runs, so it is checked against what
        # is actually installed: if something restored the attribute while a run was
        # active, the runs still in progress would otherwise go unprotected.
        if not _is_installed() and _original_init is not None:
            patched_init = _keepalive_expiry_zero(_original_init)
            httpcore.AsyncHTTPConnection.__init__ = patched_init
        _patch_depth += 1

    try:
        yield
    finally:
        with _patch_lock:
            _patch_depth -= 1
            if _patch_depth == 0:
                httpcore.AsyncHTTPConnection.__init__ = _original_init
                _original_init = None
