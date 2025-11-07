import pytest
from werkzeug.exceptions import HTTPException

from opik_backend import create_app


@pytest.fixture
def app():
    app = create_app(should_init_executor=False)
    app.config["TESTING"] = True

    if not any(rule.rule == "/error" for rule in app.url_map.iter_rules()):
        @app.route("/error")
        def error_route():
            raise ValueError("This is a test error")

    if not any(rule.rule == "/http-exception" for rule in app.url_map.iter_rules()):
        @app.route("/http-exception")
        def http_exception_route():
            http_exception = HTTPException("This is a test http exception")
            http_exception.code = 500
            raise http_exception

    return app


@pytest.fixture
def client(app):
    return app.test_client()


def test_handle_http_exception(app, client):
    response = client.get("/http-exception")

    assert response.status_code == 500
    assert response.json["error"] == "500 Internal Server Error: This is a test http exception"


@pytest.mark.parametrize(
    "debug_mode,expected_message",
    [
        (True, "This is a test error"),
        (False, "Something went wrong. Please try again later."),
    ],
)
def test_handle_exception(app, client, caplog, debug_mode, expected_message):
    app.debug = debug_mode
    response = client.get("/error")

    assert response.status_code == 500
    assert response.json["error"] == "Internal Server Error"
    assert response.json["message"] == expected_message

    assert any("Unhandled exception occurred" in m for m in caplog.messages)
