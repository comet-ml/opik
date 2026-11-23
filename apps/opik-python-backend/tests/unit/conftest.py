import pytest

from opik_backend import create_app
from opik_backend.executor_process import PROCESS_NETWORK_ISOLATION_ENV_VAR


@pytest.fixture(autouse=True)
def process_workers_unisolated(monkeypatch):
    """Isolation is on by default but needs root, which CI runners lack; tests of isolation set it themselves."""
    monkeypatch.setenv(PROCESS_NETWORK_ISOLATION_ENV_VAR, "false")


@pytest.fixture()
def app():
    app = create_app(should_init_executor=False)
    app.config.update({
        "TESTING": True,
    })

    # other setup can go here

    yield app

    # clean up / reset resources here


@pytest.fixture()
def client(app):
    return app.test_client()


@pytest.fixture()
def runner(app):
    return app.test_cli_runner()
