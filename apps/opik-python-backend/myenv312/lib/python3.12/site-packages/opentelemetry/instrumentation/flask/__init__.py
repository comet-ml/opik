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

# Note: This package is not named "flask" because of
# https://github.com/PyCQA/pylint/issues/2648

"""
This library builds on the OpenTelemetry WSGI middleware to track web requests
in Flask applications. In addition to opentelemetry-util-http, it
supports Flask-specific features such as:

* The Flask url rule pattern is used as the Span name.
* The ``http.route`` Span attribute is set so that one can see which URL rule
  matched a request.

SQLCOMMENTER
*****************************************
You can optionally configure Flask instrumentation to enable sqlcommenter which enriches
the query with contextual information.

Usage
-----

.. code:: python

    from opentelemetry.instrumentation.flask import FlaskInstrumentor

    FlaskInstrumentor().instrument(enable_commenter=True, commenter_options={})

For example, FlaskInstrumentor when used with SQLAlchemyInstrumentor or Psycopg2Instrumentor,
invoking ``cursor.execute("select * from auth_users")`` will lead to sql query
``select * from auth_users`` but when SQLCommenter is enabled the query will get appended with
some configurable tags like:

.. code::

    select * from auth_users /*metrics=value*/;"

Inorder for the commenter to append flask related tags to sql queries, the commenter needs
to enabled on the respective SQLAlchemyInstrumentor or Psycopg2Instrumentor framework too.

SQLCommenter Configurations
***************************
We can configure the tags to be appended to the sqlquery log by adding configuration
inside ``commenter_options={}`` dict.

For example, enabling this flag will add flask and it's version which
is ``/*flask%%3A2.9.3*/`` to the SQL query as a comment (default is True):

.. code:: python

    framework = True

For example, enabling this flag will add route uri ``/*route='/home'*/``
to the SQL query as a comment (default is True):

.. code:: python

    route = True

For example, enabling this flag will add controller name ``/*controller='home_view'*/``
to the SQL query as a comment (default is True):

.. code:: python

    controller = True

Usage
-----

.. code-block:: python

    from flask import Flask
    from opentelemetry.instrumentation.flask import FlaskInstrumentor

    app = Flask(__name__)

    FlaskInstrumentor().instrument_app(app)

    @app.route("/")
    def hello():
        return "Hello!"

    if __name__ == "__main__":
        app.run(debug=True)

Configuration
-------------

Exclude lists
*************
To exclude certain URLs from tracking, set the environment variable ``OTEL_PYTHON_FLASK_EXCLUDED_URLS``
(or ``OTEL_PYTHON_EXCLUDED_URLS`` to cover all instrumentations) to a string of comma delimited regexes that match the
URLs.

For example,

::

    export OTEL_PYTHON_FLASK_EXCLUDED_URLS="client/.*/info,healthcheck"

will exclude requests such as ``https://site/client/123/info`` and ``https://site/xyz/healthcheck``.

You can also pass comma delimited regexes directly to the ``instrument_app`` method:

.. code-block:: python

    FlaskInstrumentor().instrument_app(app, excluded_urls="client/.*/info,healthcheck")

Request/Response hooks
**********************

This instrumentation supports request and response hooks. These are functions that get called
right after a span is created for a request and right before the span is finished for the response.

- The client request hook is called with the internal span and an instance of WSGIEnvironment (flask.request.environ)
  when the method ``receive`` is called.
- The client response hook is called with the internal span, the status of the response and a list of key-value (tuples)
  representing the response headers returned from the response when the method ``send`` is called.

For example,

.. code-block:: python

    def request_hook(span: Span, environ: WSGIEnvironment):
        if span and span.is_recording():
            span.set_attribute("custom_user_attribute_from_request_hook", "some-value")

    def response_hook(span: Span, status: str, response_headers: List):
        if span and span.is_recording():
            span.set_attribute("custom_user_attribute_from_response_hook", "some-value")

    FlaskInstrumentor().instrument(request_hook=request_hook, response_hook=response_hook)

Flask Request object reference: https://flask.palletsprojects.com/en/2.1.x/api/#flask.Request

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

Request header names in Flask are case-insensitive and ``-`` characters are replaced by ``_``. So, giving the header
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

Response header names in Flask are case-insensitive. So, giving the header name as ``CUStom-Header`` in the environment
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

API
---
"""

import weakref
from logging import getLogger
from time import time_ns
from timeit import default_timer
from typing import Collection

import flask
from packaging import version as package_version

import opentelemetry.instrumentation.wsgi as otel_wsgi
from opentelemetry import context, trace
from opentelemetry.instrumentation._semconv import (
    _get_schema_url,
    _OpenTelemetrySemanticConventionStability,
    _OpenTelemetryStabilitySignalType,
    _report_new,
    _report_old,
    _StabilityMode,
)
from opentelemetry.instrumentation.flask.package import _instruments
from opentelemetry.instrumentation.flask.version import __version__
from opentelemetry.instrumentation.instrumentor import BaseInstrumentor
from opentelemetry.instrumentation.propagators import (
    get_global_response_propagator,
)
from opentelemetry.instrumentation.utils import _start_internal_or_server_span
from opentelemetry.metrics import get_meter
from opentelemetry.semconv.attributes.http_attributes import HTTP_ROUTE
from opentelemetry.semconv.metrics import MetricInstruments
from opentelemetry.semconv.metrics.http_metrics import (
    HTTP_SERVER_REQUEST_DURATION,
)
from opentelemetry.semconv.trace import SpanAttributes
from opentelemetry.util._importlib_metadata import version
from opentelemetry.util.http import (
    get_excluded_urls,
    parse_excluded_urls,
    sanitize_method,
)

_logger = getLogger(__name__)

_ENVIRON_STARTTIME_KEY = "opentelemetry-flask.starttime_key"
_ENVIRON_SPAN_KEY = "opentelemetry-flask.span_key"
_ENVIRON_ACTIVATION_KEY = "opentelemetry-flask.activation_key"
_ENVIRON_REQCTX_REF_KEY = "opentelemetry-flask.reqctx_ref_key"
_ENVIRON_TOKEN = "opentelemetry-flask.token"

_excluded_urls_from_env = get_excluded_urls("FLASK")

flask_version = version("flask")

if package_version.parse(flask_version) >= package_version.parse("2.2.0"):

    def _request_ctx_ref() -> weakref.ReferenceType:
        return weakref.ref(flask.globals.request_ctx._get_current_object())

else:

    def _request_ctx_ref() -> weakref.ReferenceType:
        return weakref.ref(flask._request_ctx_stack.top)


def get_default_span_name():
    method = sanitize_method(
        flask.request.environ.get("REQUEST_METHOD", "").strip()
    )
    if method == "_OTHER":
        method = "HTTP"
    try:
        span_name = f"{method} {flask.request.url_rule.rule}"
    except AttributeError:
        span_name = otel_wsgi.get_default_span_name(flask.request.environ)
    return span_name


def _rewrapped_app(
    wsgi_app,
    active_requests_counter,
    duration_histogram_old=None,
    response_hook=None,
    excluded_urls=None,
    sem_conv_opt_in_mode=_StabilityMode.DEFAULT,
    duration_histogram_new=None,
):
    def _wrapped_app(wrapped_app_environ, start_response):
        # We want to measure the time for route matching, etc.
        # In theory, we could start the span here and use
        # update_name later but that API is "highly discouraged" so
        # we better avoid it.
        wrapped_app_environ[_ENVIRON_STARTTIME_KEY] = time_ns()
        start = default_timer()
        attributes = otel_wsgi.collect_request_attributes(
            wrapped_app_environ, sem_conv_opt_in_mode
        )
        active_requests_count_attrs = (
            otel_wsgi._parse_active_request_count_attrs(
                attributes,
                sem_conv_opt_in_mode,
            )
        )

        active_requests_counter.add(1, active_requests_count_attrs)
        request_route = None

        def _start_response(status, response_headers, *args, **kwargs):
            if flask.request and (
                excluded_urls is None
                or not excluded_urls.url_disabled(flask.request.url)
            ):
                nonlocal request_route
                request_route = flask.request.url_rule

                span = flask.request.environ.get(_ENVIRON_SPAN_KEY)

                propagator = get_global_response_propagator()
                if propagator:
                    propagator.inject(
                        response_headers,
                        setter=otel_wsgi.default_response_propagation_setter,
                    )

                if span:
                    otel_wsgi.add_response_attributes(
                        span,
                        status,
                        response_headers,
                        attributes,
                        sem_conv_opt_in_mode,
                    )
                    if (
                        span.is_recording()
                        and span.kind == trace.SpanKind.SERVER
                    ):
                        custom_attributes = otel_wsgi.collect_custom_response_headers_attributes(
                            response_headers
                        )
                        if len(custom_attributes) > 0:
                            span.set_attributes(custom_attributes)
                else:
                    _logger.warning(
                        "Flask environ's OpenTelemetry span "
                        "missing at _start_response(%s)",
                        status,
                    )
                if response_hook is not None:
                    response_hook(span, status, response_headers)
            return start_response(status, response_headers, *args, **kwargs)

        result = wsgi_app(wrapped_app_environ, _start_response)
        duration_s = default_timer() - start
        if duration_histogram_old:
            duration_attrs_old = otel_wsgi._parse_duration_attrs(
                attributes, _StabilityMode.DEFAULT
            )

            if request_route:
                # http.target to be included in old semantic conventions
                duration_attrs_old[SpanAttributes.HTTP_TARGET] = str(
                    request_route
                )

            duration_histogram_old.record(
                max(round(duration_s * 1000), 0), duration_attrs_old
            )
        if duration_histogram_new:
            duration_attrs_new = otel_wsgi._parse_duration_attrs(
                attributes, _StabilityMode.HTTP
            )

            if request_route:
                duration_attrs_new[HTTP_ROUTE] = str(request_route)

            duration_histogram_new.record(
                max(duration_s, 0), duration_attrs_new
            )
        active_requests_counter.add(-1, active_requests_count_attrs)
        return result

    return _wrapped_app


def _wrapped_before_request(
    request_hook=None,
    tracer=None,
    excluded_urls=None,
    enable_commenter=True,
    commenter_options=None,
    sem_conv_opt_in_mode=_StabilityMode.DEFAULT,
):
    def _before_request():
        if excluded_urls and excluded_urls.url_disabled(flask.request.url):
            return
        flask_request_environ = flask.request.environ
        span_name = get_default_span_name()

        attributes = otel_wsgi.collect_request_attributes(
            flask_request_environ,
            sem_conv_opt_in_mode=sem_conv_opt_in_mode,
        )
        if flask.request.url_rule:
            # For 404 that result from no route found, etc, we
            # don't have a url_rule.
            attributes[SpanAttributes.HTTP_ROUTE] = flask.request.url_rule.rule
        span, token = _start_internal_or_server_span(
            tracer=tracer,
            span_name=span_name,
            start_time=flask_request_environ.get(_ENVIRON_STARTTIME_KEY),
            context_carrier=flask_request_environ,
            context_getter=otel_wsgi.wsgi_getter,
            attributes=attributes,
        )

        if request_hook:
            request_hook(span, flask_request_environ)

        if span.is_recording():
            for key, value in attributes.items():
                span.set_attribute(key, value)
            if span.is_recording() and span.kind == trace.SpanKind.SERVER:
                custom_attributes = (
                    otel_wsgi.collect_custom_request_headers_attributes(
                        flask_request_environ
                    )
                )
                if len(custom_attributes) > 0:
                    span.set_attributes(custom_attributes)

        activation = trace.use_span(span, end_on_exit=True)
        activation.__enter__()  # pylint: disable=E1101
        flask_request_environ[_ENVIRON_ACTIVATION_KEY] = activation
        flask_request_environ[_ENVIRON_REQCTX_REF_KEY] = _request_ctx_ref()
        flask_request_environ[_ENVIRON_SPAN_KEY] = span
        flask_request_environ[_ENVIRON_TOKEN] = token

        if enable_commenter:
            current_context = context.get_current()
            flask_info = {}

            # https://flask.palletsprojects.com/en/1.1.x/api/#flask.has_request_context
            if flask and flask.request:
                if commenter_options.get("framework", True):
                    flask_info["framework"] = f"flask:{flask_version}"
                if (
                    commenter_options.get("controller", True)
                    and flask.request.endpoint
                ):
                    flask_info["controller"] = flask.request.endpoint
                if (
                    commenter_options.get("route", True)
                    and flask.request.url_rule
                    and flask.request.url_rule.rule
                ):
                    flask_info["route"] = flask.request.url_rule.rule
            sqlcommenter_context = context.set_value(
                "SQLCOMMENTER_ORM_TAGS_AND_VALUES", flask_info, current_context
            )
            context.attach(sqlcommenter_context)

    return _before_request


def _wrapped_teardown_request(
    excluded_urls=None,
):
    def _teardown_request(exc):
        # pylint: disable=E1101
        if excluded_urls and excluded_urls.url_disabled(flask.request.url):
            return

        activation = flask.request.environ.get(_ENVIRON_ACTIVATION_KEY)

        original_reqctx_ref = flask.request.environ.get(
            _ENVIRON_REQCTX_REF_KEY
        )
        current_reqctx_ref = _request_ctx_ref()
        if not activation or original_reqctx_ref != current_reqctx_ref:
            # This request didn't start a span, maybe because it was created in
            # a way that doesn't run `before_request`, like when it is created
            # with `app.test_request_context`.
            #
            # Similarly, check that the request_ctx that created the span
            # matches the current request_ctx, and only tear down if they match.
            # This situation can arise if the original request_ctx handling
            # the request calls functions that push new request_ctx's,
            # like any decorated with `flask.copy_current_request_context`.

            return
        if exc is None:
            activation.__exit__(None, None, None)
        else:
            activation.__exit__(
                type(exc), exc, getattr(exc, "__traceback__", None)
            )

        if flask.request.environ.get(_ENVIRON_TOKEN, None):
            context.detach(flask.request.environ.get(_ENVIRON_TOKEN))

    return _teardown_request


class _InstrumentedFlask(flask.Flask):
    _excluded_urls = None
    _tracer_provider = None
    _request_hook = None
    _response_hook = None
    _enable_commenter = True
    _commenter_options = None
    _meter_provider = None
    _sem_conv_opt_in_mode = _StabilityMode.DEFAULT

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)

        self._original_wsgi_app = self.wsgi_app
        self._is_instrumented_by_opentelemetry = True

        meter = get_meter(
            __name__,
            __version__,
            _InstrumentedFlask._meter_provider,
            schema_url=_get_schema_url(
                _InstrumentedFlask._sem_conv_opt_in_mode
            ),
        )
        duration_histogram_old = None
        if _report_old(_InstrumentedFlask._sem_conv_opt_in_mode):
            duration_histogram_old = meter.create_histogram(
                name=MetricInstruments.HTTP_SERVER_DURATION,
                unit="ms",
                description="Measures the duration of inbound HTTP requests.",
            )
        duration_histogram_new = None
        if _report_new(_InstrumentedFlask._sem_conv_opt_in_mode):
            duration_histogram_new = meter.create_histogram(
                name=HTTP_SERVER_REQUEST_DURATION,
                unit="s",
                description="Duration of HTTP server requests.",
            )
        active_requests_counter = meter.create_up_down_counter(
            name=MetricInstruments.HTTP_SERVER_ACTIVE_REQUESTS,
            unit="requests",
            description="measures the number of concurrent HTTP requests that are currently in-flight",
        )

        self.wsgi_app = _rewrapped_app(
            self.wsgi_app,
            active_requests_counter,
            duration_histogram_old,
            _InstrumentedFlask._response_hook,
            excluded_urls=_InstrumentedFlask._excluded_urls,
            sem_conv_opt_in_mode=_InstrumentedFlask._sem_conv_opt_in_mode,
            duration_histogram_new=duration_histogram_new,
        )

        tracer = trace.get_tracer(
            __name__,
            __version__,
            _InstrumentedFlask._tracer_provider,
            schema_url=_get_schema_url(
                _InstrumentedFlask._sem_conv_opt_in_mode
            ),
        )

        _before_request = _wrapped_before_request(
            _InstrumentedFlask._request_hook,
            tracer,
            excluded_urls=_InstrumentedFlask._excluded_urls,
            enable_commenter=_InstrumentedFlask._enable_commenter,
            commenter_options=_InstrumentedFlask._commenter_options,
            sem_conv_opt_in_mode=_InstrumentedFlask._sem_conv_opt_in_mode,
        )
        self._before_request = _before_request
        self.before_request(_before_request)

        _teardown_request = _wrapped_teardown_request(
            excluded_urls=_InstrumentedFlask._excluded_urls,
        )
        self.teardown_request(_teardown_request)


class FlaskInstrumentor(BaseInstrumentor):
    # pylint: disable=protected-access,attribute-defined-outside-init
    """An instrumentor for flask.Flask

    See `BaseInstrumentor`
    """

    def instrumentation_dependencies(self) -> Collection[str]:
        return _instruments

    def _instrument(self, **kwargs):
        self._original_flask = flask.Flask
        request_hook = kwargs.get("request_hook")
        response_hook = kwargs.get("response_hook")
        if callable(request_hook):
            _InstrumentedFlask._request_hook = request_hook
        if callable(response_hook):
            _InstrumentedFlask._response_hook = response_hook
        tracer_provider = kwargs.get("tracer_provider")
        _InstrumentedFlask._tracer_provider = tracer_provider
        excluded_urls = kwargs.get("excluded_urls")
        _InstrumentedFlask._excluded_urls = (
            _excluded_urls_from_env
            if excluded_urls is None
            else parse_excluded_urls(excluded_urls)
        )
        enable_commenter = kwargs.get("enable_commenter", True)
        _InstrumentedFlask._enable_commenter = enable_commenter

        commenter_options = kwargs.get("commenter_options", {})
        _InstrumentedFlask._commenter_options = commenter_options
        meter_provider = kwargs.get("meter_provider")
        _InstrumentedFlask._meter_provider = meter_provider

        sem_conv_opt_in_mode = _OpenTelemetrySemanticConventionStability._get_opentelemetry_stability_opt_in_mode(
            _OpenTelemetryStabilitySignalType.HTTP,
        )

        _InstrumentedFlask._sem_conv_opt_in_mode = sem_conv_opt_in_mode

        flask.Flask = _InstrumentedFlask

    def _uninstrument(self, **kwargs):
        flask.Flask = self._original_flask

    # pylint: disable=too-many-locals
    @staticmethod
    def instrument_app(
        app,
        request_hook=None,
        response_hook=None,
        tracer_provider=None,
        excluded_urls=None,
        enable_commenter=True,
        commenter_options=None,
        meter_provider=None,
    ):
        if not hasattr(app, "_is_instrumented_by_opentelemetry"):
            app._is_instrumented_by_opentelemetry = False

        if not app._is_instrumented_by_opentelemetry:
            # initialize semantic conventions opt-in if needed
            _OpenTelemetrySemanticConventionStability._initialize()
            sem_conv_opt_in_mode = _OpenTelemetrySemanticConventionStability._get_opentelemetry_stability_opt_in_mode(
                _OpenTelemetryStabilitySignalType.HTTP,
            )
            excluded_urls = (
                parse_excluded_urls(excluded_urls)
                if excluded_urls is not None
                else _excluded_urls_from_env
            )
            meter = get_meter(
                __name__,
                __version__,
                meter_provider,
                schema_url=_get_schema_url(sem_conv_opt_in_mode),
            )
            duration_histogram_old = None
            if _report_old(sem_conv_opt_in_mode):
                duration_histogram_old = meter.create_histogram(
                    name=MetricInstruments.HTTP_SERVER_DURATION,
                    unit="ms",
                    description="Measures the duration of inbound HTTP requests.",
                )
            duration_histogram_new = None
            if _report_new(sem_conv_opt_in_mode):
                duration_histogram_new = meter.create_histogram(
                    name=HTTP_SERVER_REQUEST_DURATION,
                    unit="s",
                    description="Duration of HTTP server requests.",
                )
            active_requests_counter = meter.create_up_down_counter(
                name=MetricInstruments.HTTP_SERVER_ACTIVE_REQUESTS,
                unit="{request}",
                description="Number of active HTTP server requests.",
            )

            app._original_wsgi_app = app.wsgi_app
            app.wsgi_app = _rewrapped_app(
                app.wsgi_app,
                active_requests_counter,
                duration_histogram_old,
                response_hook=response_hook,
                excluded_urls=excluded_urls,
                sem_conv_opt_in_mode=sem_conv_opt_in_mode,
                duration_histogram_new=duration_histogram_new,
            )

            tracer = trace.get_tracer(
                __name__,
                __version__,
                tracer_provider,
                schema_url=_get_schema_url(sem_conv_opt_in_mode),
            )

            _before_request = _wrapped_before_request(
                request_hook,
                tracer,
                excluded_urls=excluded_urls,
                enable_commenter=enable_commenter,
                commenter_options=(
                    commenter_options if commenter_options else {}
                ),
                sem_conv_opt_in_mode=sem_conv_opt_in_mode,
            )
            app._before_request = _before_request
            app.before_request(_before_request)

            _teardown_request = _wrapped_teardown_request(
                excluded_urls=excluded_urls,
            )
            app._teardown_request = _teardown_request
            app.teardown_request(_teardown_request)
            app._is_instrumented_by_opentelemetry = True
        else:
            _logger.warning(
                "Attempting to instrument Flask app while already instrumented"
            )

    @staticmethod
    def uninstrument_app(app):
        if hasattr(app, "_original_wsgi_app"):
            app.wsgi_app = app._original_wsgi_app

            # FIXME add support for other Flask blueprints that are not None
            app.before_request_funcs[None].remove(app._before_request)
            app.teardown_request_funcs[None].remove(app._teardown_request)
            del app._original_wsgi_app
            app._is_instrumented_by_opentelemetry = False
        else:
            _logger.warning(
                "Attempting to uninstrument Flask "
                "app while already uninstrumented"
            )
