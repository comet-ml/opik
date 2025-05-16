import pytest
from typing import Dict, Any


def _generate_hint(error) -> Dict[str, Any]:
    return {"exc_info": (None, error, None)}


class FakeException(Exception):
    pass


class FakeExceptionWithStatus:
    def __init__(self, status_code):
        self.status_code = status_code


@pytest.fixture
def fake_warning_event():
    return {"level": "warning"}


@pytest.fixture
def fake_error_event():
    return {"level": "error"}


@pytest.fixture
def fake_error_event_with_status_code_401():
    return {"level": "error", "extra": {"error_tracking_extra": {"status_code": 401}}}


@pytest.fixture
def fake_error_event_with_status_code_500():
    return {"level": "error", "extra": {"error_tracking_extra": {"status_code": 500}}}


@pytest.fixture
def fake_basic_hint():
    return _generate_hint(FakeException())


@pytest.fixture
def fake_hint_with_exception_with_status_code_401():
    return _generate_hint(FakeExceptionWithStatus(401))


@pytest.fixture
def fake_hint_with_exception_with_status_code_500():
    return _generate_hint(FakeExceptionWithStatus(500))
