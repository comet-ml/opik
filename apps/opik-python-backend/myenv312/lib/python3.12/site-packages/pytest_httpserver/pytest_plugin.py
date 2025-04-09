import os

import pytest

from .httpserver import HTTPServer


class Plugin:
    SERVER = None


class PluginHTTPServer(HTTPServer):
    def start(self):
        super().start()
        Plugin.SERVER = self

    def stop(self):
        super().stop()
        Plugin.SERVER = None


def get_httpserver_listen_address():
    listen_host = os.environ.get("PYTEST_HTTPSERVER_HOST")
    listen_port = os.environ.get("PYTEST_HTTPSERVER_PORT")
    if listen_port:
        listen_port = int(listen_port)

    return listen_host, listen_port


@pytest.fixture(scope="session")
def httpserver_listen_address():
    return get_httpserver_listen_address()


@pytest.fixture(scope="session")
def httpserver_ssl_context():
    return None


@pytest.fixture(scope="session")
def make_httpserver(httpserver_listen_address, httpserver_ssl_context):
    host, port = httpserver_listen_address
    if not host:
        host = HTTPServer.DEFAULT_LISTEN_HOST
    if not port:
        port = HTTPServer.DEFAULT_LISTEN_PORT

    server = HTTPServer(host=host, port=port, ssl_context=httpserver_ssl_context)
    server.start()
    yield server
    server.clear()
    if server.is_running():
        server.stop()


def pytest_sessionfinish(session, exitstatus):  # noqa: ARG001
    if Plugin.SERVER is not None:
        Plugin.SERVER.clear()
        if Plugin.SERVER.is_running():
            Plugin.SERVER.stop()


@pytest.fixture
def httpserver(make_httpserver):
    server = make_httpserver
    server.clear()
    return server


@pytest.fixture(scope="session")
def make_httpserver_ipv4(httpserver_ssl_context):
    server = HTTPServer(host="127.0.0.1", port=0, ssl_context=httpserver_ssl_context)
    server.start()
    yield server
    server.clear()
    if server.is_running():
        server.stop()


@pytest.fixture
def httpserver_ipv4(make_httpserver_ipv4):
    server = make_httpserver_ipv4
    server.clear()
    return server


@pytest.fixture(scope="session")
def make_httpserver_ipv6(httpserver_ssl_context):
    server = HTTPServer(host="::1", port=0, ssl_context=httpserver_ssl_context)
    server.start()
    yield server
    server.clear()
    if server.is_running():
        server.stop()


@pytest.fixture
def httpserver_ipv6(make_httpserver_ipv6):
    server = make_httpserver_ipv6
    server.clear()
    return server
