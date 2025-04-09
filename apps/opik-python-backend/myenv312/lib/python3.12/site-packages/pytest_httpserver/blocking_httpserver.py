from __future__ import annotations

from queue import Empty
from queue import Queue
from typing import TYPE_CHECKING
from typing import Any

from pytest_httpserver.httpserver import METHOD_ALL
from pytest_httpserver.httpserver import UNDEFINED
from pytest_httpserver.httpserver import HeaderValueMatcher
from pytest_httpserver.httpserver import HTTPServerBase
from pytest_httpserver.httpserver import QueryMatcher
from pytest_httpserver.httpserver import RequestHandlerBase
from pytest_httpserver.httpserver import URIPattern

if TYPE_CHECKING:
    from collections.abc import Mapping
    from re import Pattern
    from ssl import SSLContext

    from werkzeug import Request
    from werkzeug import Response


class BlockingRequestHandler(RequestHandlerBase):
    """
    Provides responding to a request synchronously.

    This class should only be instantiated inside the implementation of the :py:class:`BlockingHTTPServer`.
    """

    def __init__(self):
        self.response_queue = Queue()

    def respond_with_response(self, response: Response):
        self.response_queue.put_nowait(response)


class BlockingHTTPServer(HTTPServerBase):
    """
    Server instance which enables synchronous matching for incoming requests.

    :param host: the host or IP where the server will listen
    :param port: the TCP port where the server will listen
    :param ssl_context: the ssl context object to use for https connections

    :param timeout: waiting time in seconds for matching and responding to an incoming request.
        manager

    .. py:attribute:: no_handler_status_code

        Attribute containing the http status code (int) which will be the response
        status when no matcher is found for the request. By default, it is set to *500*
        but it can be overridden to any valid http status code such as *404* if needed.
    """

    DEFAULT_LISTEN_HOST = "localhost"
    DEFAULT_LISTEN_PORT = 0  # Use ephemeral port

    def __init__(
        self,
        host=DEFAULT_LISTEN_HOST,
        port=DEFAULT_LISTEN_PORT,
        ssl_context: SSLContext | None = None,
        timeout: int = 30,
    ):
        super().__init__(host, port, ssl_context)
        self.timeout = timeout
        self.request_queue: Queue[Request] = Queue()
        self.request_handlers: dict[Request, Queue[BlockingRequestHandler]] = {}

    def assert_request(
        self,
        uri: str | URIPattern | Pattern[str],
        method: str = METHOD_ALL,
        data: str | bytes | None = None,
        data_encoding: str = "utf-8",
        headers: Mapping[str, str] | None = None,
        query_string: None | QueryMatcher | str | bytes | Mapping = None,
        header_value_matcher: HeaderValueMatcher | None = None,
        json: Any = UNDEFINED,
        timeout: int = 30,
    ) -> BlockingRequestHandler:
        """
        Wait for an incoming request and check whether it matches according to the given parameters.

        If the incoming request matches, a request handler is created and registered,
        otherwise assertion error is raised.
        The request handler can be used once to respond for the request.
        If no response is performed in the period given in the timeout parameter of the constructor
        or no request arrives in the `timeout` period, assertion error is raised.

        :param uri: URI of the request. This must be an absolute path starting with ``/``, a
            :py:class:`URIPattern` object, or a regular expression compiled by :py:func:`re.compile`.
        :param method: HTTP method of the request. If not specified (or `METHOD_ALL`
            specified), all HTTP requests will match.
        :param data: payload of the HTTP request. This could be a string (utf-8 encoded
            by default, see `data_encoding`) or a bytes object.
        :param data_encoding: the encoding used for data parameter if data is a string.
        :param headers: dictionary of the headers of the request to be matched
        :param query_string: the http query string, after ``?``, such as ``username=user``.
            If string is specified it will be encoded to bytes with the encode method of
            the string. If dict is specified, it will be matched to the ``key=value`` pairs
            specified in the request. If multiple values specified for a given key, the first
            value will be used. If multiple values needed to be handled, use ``MultiDict``
            object from werkzeug.
        :param header_value_matcher: :py:class:`HeaderValueMatcher` that matches values of headers.
        :param json: a python object (eg. a dict) whose value will be compared to the request body after it
            is loaded as json. If load fails, this matcher will be failed also. *Content-Type* is not checked.
            If that's desired, add it to the headers parameter.
        :param timeout: waiting time in seconds for an incoming request.

        :return: Created and registered :py:class:`BlockingRequestHandler`.

        Parameters `json` and `data` are mutually exclusive.
        """

        matcher = self.create_matcher(
            uri,
            method=method.upper(),
            data=data,
            data_encoding=data_encoding,
            headers=headers,
            query_string=query_string,
            header_value_matcher=header_value_matcher,
            json=json,
        )

        try:
            request = self.request_queue.get(timeout=timeout)
        except Empty:
            raise AssertionError(f"Waiting for request {matcher} timed out")  # noqa: EM102

        diff = matcher.difference(request)

        request_handler = BlockingRequestHandler()

        self.request_handlers[request].put_nowait(request_handler)

        if diff:
            request_handler.respond_with_response(self.respond_nohandler(request))
            raise AssertionError(f"Request {matcher} does not match: {diff}")  # noqa: EM102

        return request_handler

    def dispatch(self, request: Request) -> Response:
        """
        Dispatch a request for synchronous matching.

        This method queues the request for matching and waits for the request handler.
        If there was no request handler, error is responded,
        otherwise it waits for the response of request handler.
        If no response arrives, assertion error is raised, otherwise the response is returned.

        :param request: the request object from the werkzeug library.
        :return: the response object what the handler responded, or a response which contains the error.
        """

        self.request_handlers[request] = Queue()
        try:
            self.request_queue.put_nowait(request)

            try:
                request_handler = self.request_handlers[request].get(timeout=self.timeout)
            except Empty:
                return self.respond_nohandler(request)

            try:
                return request_handler.response_queue.get(timeout=self.timeout)
            except Empty:
                assertion = AssertionError(f"No response for request: {request}")
                self.add_assertion(assertion)
                raise assertion
        finally:
            del self.request_handlers[request]
