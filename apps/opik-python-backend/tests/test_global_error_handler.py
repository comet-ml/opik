import pytest
from opik_backend import create_app

@pytest.fixture
def app():
    app = create_app()
    app.config["TESTING"] = True

    if not any(rule.rule == "/error" for rule in app.url_map.iter_rules()):
        @app.route("/error")
        def error_route():
            raise ValueError("This is a test error")

    return app


@pytest.fixture
def client(app):
    return app.test_client()


def test_handle_exception_in_debug_mode(app, client, caplog):
    app.debug = True
    response = client.get("/error")

    assert response.status_code == 500
    data = response.get_json()
    assert data["error"] == "Internal Server Error"
    assert "this is a test error" in data["message"].lower()
    assert any("Unhandled exception occurred" in m for m in caplog.messages)


def test_handle_exception_in_production_mode(app, client):
    app.debug = False
    response = client.get("/error")

    assert response.status_code == 500
    data = response.get_json()
    assert data["error"] == "Internal Server Error"
    assert "please try again later" in data["message"].lower()
