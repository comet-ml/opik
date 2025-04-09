"""
This is package provides the main API for the pytest_httpserver package.

"""

__all__ = [
    "METHOD_ALL",
    "URI_DEFAULT",
    "BlockingHTTPServer",
    "BlockingRequestHandler",
    "Error",
    "HTTPServer",
    "HTTPServerError",
    "HeaderValueMatcher",
    "NoHandlerError",
    "RequestHandler",
    "RequestMatcher",
    "URIPattern",
    "WaitingSettings",
]

from .blocking_httpserver import BlockingHTTPServer
from .blocking_httpserver import BlockingRequestHandler
from .httpserver import METHOD_ALL
from .httpserver import URI_DEFAULT
from .httpserver import Error
from .httpserver import HeaderValueMatcher
from .httpserver import HTTPServer
from .httpserver import HTTPServerError
from .httpserver import NoHandlerError
from .httpserver import RequestHandler
from .httpserver import RequestMatcher
from .httpserver import URIPattern
from .httpserver import WaitingSettings
