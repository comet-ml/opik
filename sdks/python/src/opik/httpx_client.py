import gzip
import inspect
import logging
from typing import (
    Optional,
    Dict,
    Any,
    Set,
    Tuple,
    Union,
    Iterable,
    AsyncIterable,
    Mapping,
)
import httpx
import os
import urllib.parse
import json as jsonlib

from . import hooks, package_version
import platform

LOGGER = logging.getLogger(__name__)

DEPRECATION_HEADER = "X-Opik-Deprecation"


CABundlePath = str

KEEPALIVE_EXPIRY_SECONDS = 10
CONNECT_TIMEOUT_SECONDS = 20
READ_TIMEOUT_SECONDS = 100
WRITE_TIMEOUT_SECONDS = 100
POOL_TIMEOUT_SECONDS = 20

# zlib's own default. Python's gzip.compress defaults to 9 instead, which costs several
# times the CPU for well under 1% fewer bytes on Opik payloads.
DEFAULT_COMPRESSION_LEVEL = 6

# A gzip stream starts with these two bytes; JSON never does.
_GZIP_MAGIC = b"\x1f\x8b"

# What the backend uses for the same decision: Dropwizard's GzipHandlerFactory refuses to
# compress an entity below this and Opik runs it at its default. Below it gzip's framing
# can leave a body bigger than it started -- an 81-byte feedback score comes out at 93.
MIN_COMPRESSED_ENTITY_BYTES = 256


def get(
    workspace: Optional[str],
    api_key: Optional[str],
    check_tls_certificate: bool,
    compress_json_requests: bool,
    compression_level: int = DEFAULT_COMPRESSION_LEVEL,
) -> httpx.Client:
    limits = httpx.Limits(keepalive_expiry=KEEPALIVE_EXPIRY_SECONDS)

    verify: Union[bool, CABundlePath] = (
        os.environ["SSL_CERT_FILE"]
        if check_tls_certificate is True and "SSL_CERT_FILE" in os.environ
        else check_tls_certificate
    )
    # we need this to enable proxy server to analyze the request/response session during debugging
    proxy = _debug_proxy()

    timeout = httpx.Timeout(
        connect=CONNECT_TIMEOUT_SECONDS,
        read=READ_TIMEOUT_SECONDS,
        write=WRITE_TIMEOUT_SECONDS,
        pool=POOL_TIMEOUT_SECONDS,
    )

    # build HTTPX client arguments
    kwargs = {
        "limits": limits,
        "verify": verify,
        "timeout": timeout,
        "follow_redirects": True,
        "proxy": proxy,
    }
    kwargs = hooks.httpx_client_hook.build_init_arguments(kwargs)

    client = OpikHttpxClient(
        compress_json_requests=compress_json_requests,
        compression_level=compression_level,
        **kwargs,
    )

    headers = _prepare_headers(workspace=workspace, api_key=api_key)
    client.headers.update(headers)

    hooks.httpx_client_hook.apply_httpx_client_hooks(client)

    return client


def _prepare_headers(
    workspace: Optional[str], api_key: Optional[str]
) -> Dict[str, Any]:
    result = {
        "X-OPIK-DEBUG-SDK-VERSION": package_version.VERSION,
        "X-OPIK-DEBUG-PY-VERSION": platform.python_version(),
        "Accept-Encoding": "gzip",
    }

    if workspace is not None:
        result["Comet-Workspace"] = workspace

    if api_key is not None:
        result["Authorization"] = api_key

    return result


def _debug_proxy() -> Optional[str]:
    """The debugging proxy every Opik client sends through, if one is configured.

    One reader, because the upload client is built separately from the sync one: two
    lookups of the same variable are two places for them to drift apart, and a proxy that
    saw every request except dataset uploads would be a confusing thing to debug with.
    """
    return os.environ.get("_OPIK_HTTP_PROXY")


def async_twin(client: httpx.Client, max_connections: int) -> httpx.AsyncClient:
    """An `httpx.AsyncClient` configured like an already-built sync client.

    `get()` bakes auth and workspace onto the client it builds, and they are readable
    from nowhere else, so an async client built from scratch uploads unauthenticated and
    the backend answers 403. Headers are copied opaquely, never inspected.

    `max_connections` is the caller's in-flight bound: httpx's own default of 100 would
    otherwise sit below a bound the pool is allowed to exceed, and the surplus requests
    would wait out the pool timeout and fail rather than queue.
    """
    # Put back after the hooks have had their say, never merged under them: a hook
    # registered with `{"headers": ...}` would otherwise replace the copied set wholesale
    # and drop Authorization and Comet-Workspace, so uploads would 403 while every other
    # request in the process worked. `get()` has the same second pass for the same reason,
    # and the hook's own headers are already in `client.headers` by the time we read them.
    identity: Dict[str, Any] = {
        "headers": client.headers,
        "base_url": client.base_url,
        "auth": client.auth,
        "cookies": client.cookies,
    }

    settings: Dict[str, Any] = {
        **identity,
        "timeout": client.timeout,
        "follow_redirects": client.follow_redirects,
        "trust_env": client.trust_env,
        # Keepalive sized to the bound as well: capped at httpx's default of 20, every
        # connection past the twentieth would be reopened for each body it carries.
        "limits": httpx.Limits(
            max_connections=max_connections,
            max_keepalive_connections=max_connections,
            keepalive_expiry=KEEPALIVE_EXPIRY_SECONDS,
        ),
        # Resolved the same way `get()` resolves it, and from the environment rather than
        # off the client, which keeps no proxy attribute to read back. Without it the
        # debug proxy stops seeing dataset uploads.
        "proxy": _debug_proxy(),
    }

    # A hook's arguments are merged over ours and are written for a sync client, so they
    # are filtered before they reach the constructor rather than after, where a sync
    # transport surfaces as an AttributeError at request time.
    settings = _usable_async_settings(
        hooks.httpx_client_hook.build_init_arguments(settings)
    )
    settings.update(identity)

    if "transport" not in settings:
        transport = getattr(client, "_transport", None)
        if _serves_async(transport):
            # Serves async requests already, so it can carry the upload -- lent, not given.
            settings["transport"] = _BorrowedAsyncTransport(transport)
        elif "verify" not in settings:
            settings["verify"] = _verify_setting(client, transport)

    return httpx.AsyncClient(**settings)


def _serves_async(transport: Any) -> bool:
    return hasattr(transport, "handle_async_request")


class _BorrowedAsyncTransport(httpx.AsyncBaseTransport):
    """The sync client's transport, lent to the upload's client without its lifetime.

    `AsyncClient.aclose()` closes the transport it was handed, and this one belongs to the
    caller's long-lived sync client. Closing it there would leave every later request --
    upload or not -- sending through a dead connection pool, and a second `insert` would
    fail on a client that looks fine.
    """

    def __init__(self, borrowed: httpx.AsyncBaseTransport) -> None:
        self._borrowed = borrowed

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        return await self._borrowed.handle_async_request(request)

    async def aclose(self) -> None:
        """Nothing to close: the pool this sends through is not ours to end."""


class AsyncTransportUnavailable(Exception):
    """A hook's transport cannot be carried to an async client.

    Raised rather than dropped. A transport or a mount is often the only thing enforcing
    a proxy, a destination allow-list or mTLS, and an upload that quietly fell back to
    httpx's default async transport would send around that policy -- succeeding, which is
    the worst outcome of the three. The caller answers this by uploading over the sync
    client instead, which honours the hook.
    """


# A hook is registered once, so an unusable setting would otherwise be reported on every
# upload for the life of the process.
_WARNED_ASYNC_SETTINGS: Set[str] = set()


def _warn_once(key: str, message: str, *args: Any) -> None:
    if key not in _WARNED_ASYNC_SETTINGS:
        _WARNED_ASYNC_SETTINGS.add(key)
        LOGGER.warning(message, *args)


def _usable_async_settings(settings: Dict[str, Any]) -> Dict[str, Any]:
    """Drop what an `httpx.AsyncClient` cannot be built from, saying what went and why.

    A registered hook supplying `{"transport": httpx.HTTPTransport(retries=5)}` is a
    documented httpx recipe and a reasonable thing to have; it is simply not something an
    async client can use. Such a transport raises `AsyncTransportUnavailable` rather than
    being dropped, because it may be carrying policy -- see that exception. An async-capable
    one is borrowed, so closing this client does not close the caller's.

    `event_hooks` is different, and is filtered rather than refused: a sync hook is an
    observer, so losing it costs logging rather than enforcement.

    Only the three settings that carry a sync-only *object* are filtered. Names are not,
    deliberately: `httpx.Client` and `httpx.AsyncClient` take the same arguments, and
    `get()` has already built a sync client from this very dict, so an unknown name cannot
    reach here -- while deciding one by reflection over `AsyncClient.__init__` would strip
    every setting, auth headers included, whenever anything has wrapped that method.
    """
    usable: Dict[str, Any] = {}

    for name, value in settings.items():
        if name == "transport":
            if not _serves_async(value):
                raise AsyncTransportUnavailable(
                    f"the httpx 'transport' supplied by an Opik httpx client hook "
                    f"({type(value).__name__}) serves sync requests only"
                )
            # Lent, not given: a hook holds one dict of arguments, so this is the same
            # object the caller's long-lived sync client sends through, and
            # `AsyncClient.aclose()` would close it out from under every later request.
            value = _BorrowedAsyncTransport(value)

        if name == "mounts" and isinstance(value, Mapping):
            unusable = [
                pattern
                for pattern, mounted in value.items()
                if mounted is not None and not _serves_async(mounted)
            ]
            if unusable:
                raise AsyncTransportUnavailable(
                    "the httpx 'mounts' supplied by an Opik httpx client hook serve sync "
                    f"requests only: {', '.join(sorted(unusable))}"
                )
            # Borrowed for the same reason as the transport above.
            value = {
                pattern: None if mounted is None else _BorrowedAsyncTransport(mounted)
                for pattern, mounted in value.items()
            }

        if name == "event_hooks" and isinstance(value, Mapping):
            # `or ()` because a hook may supply `{"request": None}`, and iterating that
            # would fail the upload before it began.
            value = {
                event: [
                    fn for fn in (callables or ()) if inspect.iscoroutinefunction(fn)
                ]
                for event, callables in value.items()
            }
            if value != settings[name]:
                _warn_once(
                    name,
                    "Ignoring the non-async httpx 'event_hooks' supplied by an Opik httpx "
                    "client hook for the dataset upload client: an async client awaits "
                    "its hooks, so a plain function cannot run there.",
                )

        usable[name] = value

    return usable


def _verify_setting(client: httpx.Client, transport: Any) -> Union[bool, Any]:
    """The TLS verification the sync client was built with.

    `verify` cannot be read back off a built httpx client, so `get()` records what it
    resolved; anything else -- a REST client built directly, sending through a plain
    `httpx.Client` -- falls back to the SSL context its transport holds.
    """
    recorded = getattr(client, "verify_setting", None)
    if recorded is not None:
        return recorded

    ssl_context = getattr(getattr(transport, "_pool", None), "_ssl_context", None)
    if ssl_context is not None:
        return ssl_context

    # Loud, because the default this falls back to is "verify against the system store":
    # exactly the wrong answer for an install with a private CA or verification turned off,
    # and one that would fail dataset uploads alone while every other request worked.
    LOGGER.warning(
        "Could not determine the TLS verification setting for the dataset upload client; "
        "falling back to the httpx default. A custom CA bundle or "
        "OPIK_CHECK_TLS_CERTIFICATE=false may not apply to dataset uploads."
    )
    return True


def compresses_json_requests(client: httpx.Client, default: bool = True) -> bool:
    """Whether bodies sent through `client` are expected to be gzipped.

    One reading of the setting for both the code that produces a prepared body and the
    code that labels it, so the `Content-Encoding` header cannot disagree with the bytes.

    A plain `httpx.Client` carries no such setting -- which is what a REST client built
    directly rather than by `Opik` sends through -- so the caller supplies the default it
    would otherwise have had, rather than this assuming one.
    """
    compress: bool = getattr(client, "compress_json_requests", default)
    return compress


def wrapper_headers(rest_client: Any) -> Dict[str, str]:
    """The headers the generated client applies to each request it sends.

    Auth and workspace live on the generated client's *wrapper*, not on the httpx client,
    whenever the REST client was built directly -- `OpikApi(api_key=..., workspace_name=...)`.
    A client built by `Opik` carries them on the httpx client instead, so this repeats
    what is already there. Sending a prepared body talks to the httpx client, so without
    this a standalone REST client would upload unauthenticated.

    Returns nothing for a REST client that is not a generated one, which has no wrapper.
    """
    wrapper = getattr(rest_client, "_client_wrapper", None)
    get_headers = getattr(wrapper, "get_headers", None)
    headers = get_headers() if callable(get_headers) else None
    if not isinstance(headers, dict):
        return {}
    return {key: value for key, value in headers.items() if isinstance(value, str)}


def send_prepared_json(
    client: httpx.Client,
    base_url: str,
    path: str,
    body: bytes,
    headers: Optional[Dict[str, str]] = None,
) -> httpx.Response:
    """PUT an already-serialised JSON body.

    Exists so a caller that has produced the request body itself can send it without a
    second serialisation pass. Auth and workspace headers ride on `client`, which is the
    same client the generated REST client sends through, so this does not depend on the
    generated client's internals. The body is declared gzipped when it is gzipped -- read
    off the bytes rather than from a setting, so the header cannot disagree with what it
    describes however the producer was configured.
    """
    url, request_headers = _prepared_json_request(base_url, path, body, headers)
    return client.request("PUT", url, content=body, headers=request_headers)


async def send_prepared_json_async(
    client: httpx.AsyncClient,
    base_url: str,
    path: str,
    body: bytes,
    headers: Optional[Dict[str, str]] = None,
) -> httpx.Response:
    """Async twin of `send_prepared_json`, for uploads sent over the asyncio pool."""
    url, request_headers = _prepared_json_request(base_url, path, body, headers)
    return await client.request("PUT", url, content=body, headers=request_headers)


def _prepared_json_request(
    base_url: str, path: str, body: bytes, headers: Optional[Dict[str, str]]
) -> Tuple[str, Dict[str, str]]:
    url = urllib.parse.urljoin(
        base_url if base_url.endswith("/") else base_url + "/", path
    )
    # Ours last: the caller's headers carry identity, never the framing of this request.
    request_headers = {
        **(headers or {}),
        "Content-Type": "application/json;charset=utf-8",
    }
    if body.startswith(_GZIP_MAGIC):
        request_headers["Content-Encoding"] = "gzip"
    return url, request_headers


class OpikHttpxClient(httpx.Client):
    def __init__(
        self,
        compress_json_requests: bool = True,
        compression_level: int = DEFAULT_COMPRESSION_LEVEL,
        **kwargs: Any,
    ) -> None:
        super().__init__(**kwargs)
        self.compress_json_requests = compress_json_requests
        self.compression_level = compression_level
        # httpx keeps no readable `verify`, and the dataset upload needs the same one for
        # a client of its own. Recorded after the hooks have had the kwargs, so an
        # override reaches the upload too.
        self.verify_setting = kwargs.get("verify", True)
        self.warnings: Dict[str, bool] = {}

    def build_request(
        self,
        method: str,
        url: Union[httpx.URL, str],
        *,
        content: Optional[
            Union[str, bytes, Iterable[bytes], AsyncIterable[bytes]]
        ] = None,
        data: Optional[Mapping[str, Any]] = None,
        files: Any = None,
        json: Any = None,
        params: Any = None,
        headers: Any = None,
        cookies: Any = None,
        timeout: Any = httpx.USE_CLIENT_DEFAULT,
        extensions: Any = None,
    ) -> httpx.Request:
        # we override this method to allow compression of JSON requests that is handled
        # by httpx.Client.request() as well as by httpx.Client.stream() (both used in the OPIK)
        if self.compress_json_requests:
            if method in ("POST", "PUT", "PATCH") and json is not None:
                # Serialised here whatever the size, so only *whether it is gzipped*
                # turns on the size. Handing a small body back to httpx instead would
                # re-encode it with different settings -- compact separators, and
                # `allow_nan=False`, which raises on a NaN this encoder writes -- and a
                # body would then be accepted or rejected according to its length.
                json_data = jsonlib.dumps(json).encode("utf-8")
                content = json_data
                json = None
                # Copied rather than mutated: the caller's dict is not ours to edit.
                headers = {} if headers is None else dict(headers)
                if len(json_data) >= MIN_COMPRESSED_ENTITY_BYTES:
                    content = gzip.compress(json_data, self.compression_level)
                    headers["Content-Encoding"] = "gzip"
                else:
                    # A caller's gzip label would otherwise outlive the body it described
                    # and the server would try to inflate plain JSON. Only gzip is
                    # dropped; any other encoding the caller set is theirs to keep.
                    for name in [
                        key for key in headers if key.lower() == "content-encoding"
                    ]:
                        if str(headers[name]).lower() == "gzip":
                            del headers[name]
                headers["Content-Length"] = str(len(content))
                if not any(key.lower() == "content-type" for key in headers):
                    # to avoid having it in headers two times with different cases in keys (e.g., streaming operations)
                    headers["Content-Type"] = "application/json;charset=utf-8"

        return super().build_request(
            method=method,
            url=url,
            content=content,
            data=data,
            files=files,
            json=json,
            params=params,
            headers=headers,
            cookies=cookies,
            timeout=timeout,
            extensions=extensions,
        )

    def send(self, request: httpx.Request, **kwargs: Any) -> httpx.Response:
        response = super().send(request, **kwargs)
        deprecation_message = response.headers.get(DEPRECATION_HEADER)
        if deprecation_message:
            message = "Deprecation warning for %s %s: %s"
            request_key = f"{request.method}:{request.url.path}"
            if request_key not in self.warnings:
                self.warnings[request_key] = True
                LOGGER.warning(
                    message, request.method, request.url, deprecation_message
                )

        return response
