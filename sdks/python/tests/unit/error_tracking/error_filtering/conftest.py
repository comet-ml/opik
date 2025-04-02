from unittest import mock
import pytest
from typing import Dict, Any


def _generate_hint(error) -> Dict[str, Any]:
    return {"exc_info": (None, error, None)}


class FakeException(Exception):
    pass


@pytest.fixture
def mocked_warning_event():
    mocked_warning_event = mock.Mock()
    mocked_warning_event.get.return_value = "warning"

    return mocked_warning_event


@pytest.fixture
def mocked_error_event():
    mocked_error_event = mock.Mock()
    mocked_error_event.get.return_value = "error"

    return mocked_error_event


@pytest.fixture
def basic_hint():
    return _generate_hint(FakeException())
