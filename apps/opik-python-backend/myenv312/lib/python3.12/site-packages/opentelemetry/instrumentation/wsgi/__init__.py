# Copyright The OpenTelemetry Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""
This library provides a WSGI middleware that can be used on any WSGI framework
(such as Django / Flask / Web.py) to track requests timing through OpenTelemetry.

Usage (Flask)
-------------

.. code-block:: python

    from flask import Flask
    from opentelemetry.instrumentation.wsgi import OpenTelemetryMiddleware

    app = Flask(__name__)
    app.wsgi_app = OpenTelemetryMiddleware(app.wsgi_app)

    @app.route("/")
    def hello():
        return "Hello!"

    if __name__ == "__main__":
        app.run(debug=True)


Usage (Django)
--------------

Modify the application's ``wsgi.py`` file as shown below.

.. code-block:: python

    import os
    from opentelemetry.instrumentation.wsgi import OpenTelemetryMiddleware
    from django.core.wsgi import get_wsgi_application

    os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'application.settings')

    application = get_wsgi_application()
    application = OpenTelemetryMiddleware(application)

Usage (Web.py)
--------------

.. code-block:: python

    import web
    from opentelemetry.instrumentation.wsgi import OpenTelemetryMiddleware
    from cheroot import wsgi

    urls = ('/', 'index')


    class index:

        def GET(self):
            return "Hello, world!"


    if __name__ == "__main__":
        app = web.application(urls, globals())
        func = app.wsgifunc()

        func = OpenTelemetryMiddleware(func)

        server = wsgi.WSGIServer(
            ("localhost", 5100), func, server_name="localhost"
        )
        server.start()

Configuration
-------------

Request/Response hooks
**********************

This instrumentation supports request and response hooks. These are functions that get called
right after a span is created for a request and right before the span is finished for the response.

- The client request hook is called with the internal span and an instance of WSGIEnvironment when the method
  ``receive`` is called.
- The client response hook is called with the internal span, the status of the response and a list of key-value (tuples)
  representing the response headers returned from the response when the method ``send`` is called.

For example,

.. code-block:: python

    from wsgiref.types import WSGIEnvironment, StartResponse
    from opentelemetry.instrumentation.wsgi import OpenTelemetryMiddleware

    def app(environ: WSGIEnvironment, start_response: StartResponse):
        start_response("200 OK", [("Content-Type", "text/plain"), ("Content-Length", "13")])
        return [b"Hello, World!"]

    def request_hook(span: Span, environ: WSGIEnvironment):
        if span and span.is_recording():
            span.set_attribute("custom_user_attribute_from_request_hook", "some-value")

    def response_hook(span: Span, environ: WSGIEnvironment, status: str, response_headers: list[tuple[str, str]]):
        if span and span.is_recording():
            span.set_attribute("custom_user_attribute_from_response_hook", "some-value")

    OpenTelemetryMiddleware(app, request_hook=request_hook, response_hook=response_hook)

Capture HTTP request and response headers
*****************************************
You can configure the agent to capture specified HTTP headers as span attributes, according to the
`semantic convention <https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/semantic_conventions/http.md#http-request-and-response-headers>`_.

Request headers
***************
To capture HTTP request headers as span attributes, set the environment variable
``OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_SERVER_REQUEST`` to a comma delimited list of HTTP header names.

For example,
::

    export OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_SERVER_REQUEST="content-type,custom_request_header"

will extract ``content-type`` and ``custom_request_header`` from the request headers and add them as span attributes.

Request header names in WSGI are case-insensitive and ``-`` characters are replaced by ``_``. So, giving the header
name as ``CUStom_Header`` in the environment variable will capture the header named ``custom-header``.

Regular expressions may also be used to match multiple headers that correspond to the given pattern.  For example:
::

    export OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_SERVER_REQUEST="Accept.*,X-.*"

Would match all request headers that start with ``Accept`` and ``X-``.

To capture all request headers, set ``OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_SERVER_REQUEST`` to ``".*"``.
::

    export OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_SERVER_REQUEST=".*"

The name of the added span attribute will follow the format ``http.request.header.<header_name>`` where ``<header_name>``
is the normalized HTTP header name (lowercase, with ``-`` replaced by ``_``). The value of the attribute will be a
single item list containing all the header values.

For example:
``http.request.header.custom_request_header = ["<value1>,<value2>"]``

Response headers
****************
To capture HTTP response headers as span attributes, set the environment variable
``OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_SERVER_RESPONSE`` to a comma delimited list of HTTP header names.

For example,
::

    export OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_SERVER_RESPONSE="content-type,custom_response_header"

will extract ``content-type`` and ``custom_response_header`` from the response headers and add them as span attributes.

Response header names in WSGI are case-insensitive. So, giving the header name as ``CUStom-Header`` in the environment
variable will capture the header named ``custom-header``.

Regular expressions may also be used to match multiple headers that correspond to the given pattern.  For example:
::

    export OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_SERVER_RESPONSE="Content.*,X-.*"

Would match all response headers that start with ``Content`` and ``X-``.

To capture all response headers, set ``OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_SERVER_RESPONSE`` to ``".*"``.
::

    export OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_SERVER_RESPONSE=".*"

The name of the added span attribute will follow the format ``http.response.header.<header_name>`` where ``<header_name>``
is the normalized HTTP header name (lowercase, with ``-`` replaced by ``_``). The value of the attribute will be a
single item list containing all the header values.

For example:
``http.response.header.custom_response_header = ["<value1>,<value2>"]``

Sanitizing headers
******************
In order to prevent storing sensitive data such as personally identifiable information (PII), session keys, passwords,
etc, set the environment variable ``OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_SANITIZE_FIELDS``
to a comma delimited list of HTTP header names to be sanitized.  Regexes may be used, and all header names will be
matched in a case-insensitive manner.

For example,
::

    export OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_SANITIZE_FIELDS=".*session.*,set-cookie"

will replace the value of headers such as ``session-id`` and ``set-cookie`` with ``[REDACTED]`` in the span.

Note:
    The environment variable names used to capture HTTP headers are still experimental, and thus are subject to change.

Sanitizing methods
******************
In order to prevent unbound cardinality for HTTP methods by default nonstandard ones are labeled as ``NONSTANDARD``.
To record all of the names set the environment variable  ``OTEL_PYTHON_INSTRUMENTATION_HTTP_CAPTURE_ALL_METHODS``
to a value that evaluates to true, e.g. ``1``.

API
---
"""

from __future__ import annotations

import functools
import wsgiref.util as wsgiref_util
from timeit import default_timer
from typing import TYPE_CHECKING, Any, Callable, Dict, Iterable, TypeVar, cast

from opentelemetry import context, trace
from opentelemetry.instrumentation._semconv import (
    _filter_semconv_active_request_count_attr,
    _filter_semconv_duration_attrs,
    _get_schema_url,
    _OpenTelemetrySemanticConventionStability,
    _OpenTelemetryStabilitySignalType,
    _report_new,
    _report_old,
    _server_active_requests_count_attrs_new,
    _server_active_requests_count_attrs_old,
    _server_duration_attrs_new,
    _server_duration_attrs_old,
    _set_http_flavor_version,
    _set_http_method,
    _set_http_net_host,
    _set_http_net_host_port,
    _set_http_net_peer_name_server,
    _set_http_peer_ip_server,
    _set_http_peer_port_server,
    _set_http_scheme,
    _set_http_target,
    _set_http_user_agent,
    _set_status,
    _StabilityMode,
)
from opentelemetry.instrumentation.utils import _start_internal_or_server_span
from opentelemetry.instrumentation.wsgi.version import __version__
from opentelemetry.metrics import MeterProvider, get_meter
from opentelemetry.propagators.textmap import Getter
from opentelemetry.semconv.attributes.error_attributes import ERROR_TYPE
from opentelemetry.semconv.metrics import MetricInstruments
from opentelemetry.semconv.metrics.http_metrics import (
    HTTP_SERVER_REQUEST_DURATION,
)
from opentelemetry.semconv.trace import SpanAttributes
from opentelemetry.trace import TracerProvider
from opentelemetry.trace.status import Status, StatusCode
from opentelemetry.util.http import (
    OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_SANITIZE_FIELDS,
    OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_SERVER_REQUEST,
    OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_SERVER_RESPONSE,
    SanitizeValue,
    _parse_url_query,
    get_custom_headers,
    normalise_request_header_name,
    normalise_response_header_name,
    remove_url_credentials,
    sanitize_method,
)

if TYPE_CHECKING:
    from wsgiref.types import StartResponse, WSGIApplication, WSGIEnvironment


T = TypeVar("T")
RequestHook = Callable[[trace.Span, "WSGIEnvironment"], None]
ResponseHook = Callable[
    [trace.Span, "WSGIEnvironment", str, "list[tuple[str, str]]"], None
]

_HTTP_VERSION_PREFIX = "HTTP/"
_CARRIER_KEY_PREFIX = "HTTP_"
_CARRIER_KEY_PREFIX_LEN = len(_CARRIER_KEY_PREFIX)


class WSGIGetter(Getter[Dict[str, Any]]):
    def get(self, carrier: dict[str, Any], key: str) -> list[str] | None:
        """Getter implementation to retrieve a HTTP header value from the
             PEP3333-conforming WSGI environ

        Args:
             carrier: WSGI environ object
             key: header name in environ object
         Returns:
             A list with a single string with the header value if it exists,
             else None.
        """
        environ_key = "HTTP_" + key.upper().replace("-", "_")
        value = carrier.get(environ_key)
        if value is not None:
            return [value]
        return None

    def keys(self, carrier: dict[str, Any]):
        return [
            key[_CARRIER_KEY_PREFIX_LEN:].lower().replace("_", "-")
            for key in carrier
            if key.startswith(_CARRIER_KEY_PREFIX)
        ]


wsgi_getter = WSGIGetter()


# pylint: disable=too-many-branches
def collect_request_attributes(
    environ: WSGIEnvironment,
    sem_conv_opt_in_mode: _StabilityMode = _StabilityMode.DEFAULT,
):
    """Collects HTTP request attributes from the PEP3333-conforming
    WSGI environ and returns a dictionary to be used as span creation attributes.
    """
    result: dict[str, str | None] = {}
    _set_http_method(
        result,
        environ.get("REQUEST_METHOD", ""),
        sanitize_method(cast(str, environ.get("REQUEST_METHOD", ""))),
        sem_conv_opt_in_mode,
    )
    # old semconv v1.12.0
    server_name = environ.get("SERVER_NAME")
    if _report_old(sem_conv_opt_in_mode):
        result[SpanAttributes.HTTP_SERVER_NAME] = server_name

    _set_http_scheme(
        result,
        environ.get("wsgi.url_scheme"),
        sem_conv_opt_in_mode,
    )

    host = environ.get("HTTP_HOST")
    host_port = environ.get("SERVER_PORT")
    if host:
        _set_http_net_host(result, host, sem_conv_opt_in_mode)
        # old semconv v1.12.0
        if _report_old(sem_conv_opt_in_mode):
            result[SpanAttributes.HTTP_HOST] = host
    if host_port:
        _set_http_net_host_port(
            result,
            int(host_port),
            sem_conv_opt_in_mode,
        )

    target = environ.get("RAW_URI")
    if target is None:  # Note: `"" or None is None`
        target = environ.get("REQUEST_URI")
    if target:
        path, query = _parse_url_query(target)
        _set_http_target(result, target, path, query, sem_conv_opt_in_mode)
    else:
        # old semconv v1.20.0
        if _report_old(sem_conv_opt_in_mode):
            result[SpanAttributes.HTTP_URL] = remove_url_credentials(
                wsgiref_util.request_uri(environ)
            )

    remote_addr = environ.get("REMOTE_ADDR")
    if remote_addr:
        _set_http_peer_ip_server(result, remote_addr, sem_conv_opt_in_mode)

    peer_port = environ.get("REMOTE_PORT")
    if peer_port:
        _set_http_peer_port_server(result, peer_port, sem_conv_opt_in_mode)

    remote_host = environ.get("REMOTE_HOST")
    if remote_host and remote_host != remote_addr:
        _set_http_net_peer_name_server(
            result, remote_host, sem_conv_opt_in_mode
        )

    user_agent = environ.get("HTTP_USER_AGENT")
    if user_agent is not None and len(user_agent) > 0:
        _set_http_user_agent(result, user_agent, sem_conv_opt_in_mode)

    flavor = environ.get("SERVER_PROTOCOL", "")
    if flavor.upper().startswith(_HTTP_VERSION_PREFIX):
        flavor = flavor[len(_HTTP_VERSION_PREFIX) :]
    if flavor:
        _set_http_flavor_version(result, flavor, sem_conv_opt_in_mode)

    return result


def collect_custom_request_headers_attributes(environ: WSGIEnvironment):
    """Returns custom HTTP request headers which are configured by the user
    from the PEP3333-conforming WSGI environ to be used as span creation attributes as described
    in the specification https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/semantic_conventions/http.md#http-request-and-response-headers
    """

    sanitize = SanitizeValue(
        get_custom_headers(
            OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_SANITIZE_FIELDS
        )
    )
    headers = {
        key[_CARRIER_KEY_PREFIX_LEN:].replace("_", "-"): val
        for key, val in environ.items()
        if key.startswith(_CARRIER_KEY_PREFIX)
    }

    return sanitize.sanitize_header_values(
        headers,
        get_custom_headers(
            OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_SERVER_REQUEST
        ),
        normalise_request_header_name,
    )


def collect_custom_response_headers_attributes(
    response_headers: list[tuple[str, str]],
):
    """Returns custom HTTP response headers which are configured by the user from the
    PEP3333-conforming WSGI environ as described in the specification
    https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/semantic_conventions/http.md#http-request-and-response-headers
    """

    sanitize = SanitizeValue(
        get_custom_headers(
            OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_SANITIZE_FIELDS
        )
    )
    response_headers_dict: dict[str, str] = {}
    if response_headers:
        for key, val in response_headers:
            key = key.lower()
            if key in response_headers_dict:
                response_headers_dict[key] += "," + val
            else:
                response_headers_dict[key] = val

    return sanitize.sanitize_header_values(
        response_headers_dict,
        get_custom_headers(
            OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_SERVER_RESPONSE
        ),
        normalise_response_header_name,
    )


# TODO: Used only on the `opentelemetry-instrumentation-pyramid` package - It can be moved there.
def _parse_status_code(resp_status: str) -> int | None:
    status_code, _ = resp_status.split(" ", 1)
    try:
        return int(status_code)
    except ValueError:
        return None


def _parse_active_request_count_attrs(
    req_attrs, sem_conv_opt_in_mode: _StabilityMode = _StabilityMode.DEFAULT
):
    return _filter_semconv_active_request_count_attr(
        req_attrs,
        _server_active_requests_count_attrs_old,
        _server_active_requests_count_attrs_new,
        sem_conv_opt_in_mode,
    )


def _parse_duration_attrs(
    req_attrs: dict[str, str | None],
    sem_conv_opt_in_mode: _StabilityMode = _StabilityMode.DEFAULT,
):
    return _filter_semconv_duration_attrs(
        req_attrs,
        _server_duration_attrs_old,
        _server_duration_attrs_new,
        sem_conv_opt_in_mode,
    )


def add_response_attributes(
    span: trace.Span,
    start_response_status: str,
    response_headers: list[tuple[str, str]],
    duration_attrs: dict[str, str | None] | None = None,
    sem_conv_opt_in_mode: _StabilityMode = _StabilityMode.DEFAULT,
):  # pylint: disable=unused-argument
    """Adds HTTP response attributes to span using the arguments
    passed to a PEP3333-conforming start_response callable.
    """
    status_code_str, _ = start_response_status.split(" ", 1)
    try:
        status_code = int(status_code_str)
    except ValueError:
        status_code = -1
    if duration_attrs is None:
        duration_attrs = {}
    _set_status(
        span,
        duration_attrs,
        status_code,
        status_code_str,
        server_span=True,
        sem_conv_opt_in_mode=sem_conv_opt_in_mode,
    )


def get_default_span_name(environ: WSGIEnvironment) -> str:
    """
    Default span name is the HTTP method and URL path, or just the method.
    https://github.com/open-telemetry/opentelemetry-specification/pull/3165
    https://opentelemetry.io/docs/reference/specification/trace/semantic_conventions/http/#name

    Args:
        environ: The WSGI environ object.
    Returns:
        The span name.
    """
    method = sanitize_method(
        cast(str, environ.get("REQUEST_METHOD", "")).strip()
    )
    if method == "_OTHER":
        return "HTTP"
    path = cast(str, environ.get("PATH_INFO", "")).strip()
    if method and path:
        return f"{method} {path}"
    return method


class OpenTelemetryMiddleware:
    """The WSGI application middleware.

    This class is a PEP 3333 conforming WSGI middleware that starts and
    annotates spans for any requests it is invoked with.

    Args:
        wsgi: The WSGI application callable to forward requests to.
        request_hook: Optional callback which is called with the server span and WSGI
                      environ object for every incoming request.
        response_hook: Optional callback which is called with the server span,
                       WSGI environ, status_code and response_headers for every
                       incoming request.
        tracer_provider: Optional tracer provider to use. If omitted the current
                         globally configured one is used.
        meter_provider: Optional meter provider to use. If omitted the current
                         globally configured one is used.
    """

    def __init__(
        self,
        wsgi: WSGIApplication,
        request_hook: RequestHook | None = None,
        response_hook: ResponseHook | None = None,
        tracer_provider: TracerProvider | None = None,
        meter_provider: MeterProvider | None = None,
    ):
        # initialize semantic conventions opt-in if needed
        _OpenTelemetrySemanticConventionStability._initialize()
        sem_conv_opt_in_mode = _OpenTelemetrySemanticConventionStability._get_opentelemetry_stability_opt_in_mode(
            _OpenTelemetryStabilitySignalType.HTTP,
        )
        self.wsgi = wsgi
        self.tracer = trace.get_tracer(
            __name__,
            __version__,
            tracer_provider,
            schema_url=_get_schema_url(sem_conv_opt_in_mode),
        )
        self.meter = get_meter(
            __name__,
            __version__,
            meter_provider,
            schema_url=_get_schema_url(sem_conv_opt_in_mode),
        )
        self.duration_histogram_old = None
        if _report_old(sem_conv_opt_in_mode):
            self.duration_histogram_old = self.meter.create_histogram(
                name=MetricInstruments.HTTP_SERVER_DURATION,
                unit="ms",
                description="Measures the duration of inbound HTTP requests.",
            )
        self.duration_histogram_new = None
        if _report_new(sem_conv_opt_in_mode):
            self.duration_histogram_new = self.meter.create_histogram(
                name=HTTP_SERVER_REQUEST_DURATION,
                unit="s",
                description="Duration of HTTP server requests.",
            )
        # We don't need a separate active request counter for old/new semantic conventions
        # because the new attributes are a subset of the old attributes
        self.active_requests_counter = self.meter.create_up_down_counter(
            name=MetricInstruments.HTTP_SERVER_ACTIVE_REQUESTS,
            unit="{request}",
            description="Number of active HTTP server requests.",
        )
        self.request_hook = request_hook
        self.response_hook = response_hook
        self._sem_conv_opt_in_mode = sem_conv_opt_in_mode

    @staticmethod
    def _create_start_response(
        span: trace.Span,
        start_response: StartResponse,
        response_hook: Callable[[str, list[tuple[str, str]]], None] | None,
        duration_attrs: dict[str, str | None],
        sem_conv_opt_in_mode: _StabilityMode,
    ):
        @functools.wraps(start_response)
        def _start_response(
            status: str,
            response_headers: list[tuple[str, str]],
            *args: Any,
            **kwargs: Any,
        ):
            add_response_attributes(
                span,
                status,
                response_headers,
                duration_attrs,
                sem_conv_opt_in_mode,
            )
            if span.is_recording() and span.kind == trace.SpanKind.SERVER:
                custom_attributes = collect_custom_response_headers_attributes(
                    response_headers
                )
                if len(custom_attributes) > 0:
                    span.set_attributes(custom_attributes)
            if response_hook:
                response_hook(status, response_headers)
            return start_response(status, response_headers, *args, **kwargs)

        return _start_response

    # pylint: disable=too-many-branches
    def __call__(
        self, environ: WSGIEnvironment, start_response: StartResponse
    ):
        """The WSGI application

        Args:
            environ: A WSGI environment.
            start_response: The WSGI start_response callable.
        """
        req_attrs = collect_request_attributes(
            environ, self._sem_conv_opt_in_mode
        )
        active_requests_count_attrs = _parse_active_request_count_attrs(
            req_attrs,
            self._sem_conv_opt_in_mode,
        )

        span, token = _start_internal_or_server_span(
            tracer=self.tracer,
            span_name=get_default_span_name(environ),
            start_time=None,
            context_carrier=environ,
            context_getter=wsgi_getter,
            attributes=req_attrs,
        )
        if span.is_recording() and span.kind == trace.SpanKind.SERVER:
            custom_attributes = collect_custom_request_headers_attributes(
                environ
            )
            if len(custom_attributes) > 0:
                span.set_attributes(custom_attributes)

        if self.request_hook:
            self.request_hook(span, environ)

        response_hook = self.response_hook
        if response_hook:
            response_hook = functools.partial(response_hook, span, environ)

        start = default_timer()
        self.active_requests_counter.add(1, active_requests_count_attrs)
        try:
            with trace.use_span(span):
                start_response = self._create_start_response(
                    span,
                    start_response,
                    response_hook,
                    req_attrs,
                    self._sem_conv_opt_in_mode,
                )
                iterable = self.wsgi(environ, start_response)
                return _end_span_after_iterating(iterable, span, token)
        except Exception as ex:
            if _report_new(self._sem_conv_opt_in_mode):
                req_attrs[ERROR_TYPE] = type(ex).__qualname__
                if span.is_recording():
                    span.set_attribute(ERROR_TYPE, type(ex).__qualname__)
                span.set_status(Status(StatusCode.ERROR, str(ex)))
            span.end()
            if token is not None:
                context.detach(token)
            raise
        finally:
            duration_s = default_timer() - start
            if self.duration_histogram_old:
                duration_attrs_old = _parse_duration_attrs(
                    req_attrs, _StabilityMode.DEFAULT
                )
                self.duration_histogram_old.record(
                    max(round(duration_s * 1000), 0), duration_attrs_old
                )
            if self.duration_histogram_new:
                duration_attrs_new = _parse_duration_attrs(
                    req_attrs, _StabilityMode.HTTP
                )
                self.duration_histogram_new.record(
                    max(duration_s, 0), duration_attrs_new
                )
            self.active_requests_counter.add(-1, active_requests_count_attrs)


# Put this in a subfunction to not delay the call to the wrapped
# WSGI application (instrumentation should change the application
# behavior as little as possible).
def _end_span_after_iterating(
    iterable: Iterable[T], span: trace.Span, token: object
) -> Iterable[T]:
    try:
        with trace.use_span(span):
            yield from iterable
    finally:
        close = getattr(iterable, "close", None)
        if close:
            close()
        span.end()
        if token is not None:
            context.detach(token)


# TODO: inherit from opentelemetry.instrumentation.propagators.Setter
class ResponsePropagationSetter:
    def set(self, carrier: list[tuple[str, T]], key: str, value: T):  # pylint: disable=no-self-use
        carrier.append((key, value))


default_response_propagation_setter = ResponsePropagationSetter()
