import pytest
from testix import *

from comet_llm import exceptions


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(exceptions, "LOGGER")


def test_filter__no_exceptions_raised__nothing_done():
    NOT_USED = None
    @exceptions.filter(allow_raising=True, summary=NOT_USED)
    def f():
        return 42

    assert f() == 42


def test_filter__upraising_allowed__function_raised_exception__exception_raised_to_user():
    @exceptions.filter(allow_raising=True, summary=Fake("summary"))
    def f():
        raise Exception("some-message")

    with Scenario() as s:
        s.summary.increment_failed()
        with pytest.raises(Exception):
            f()


def test_filter__upraising_not_allowed__function_raised_exception__exception_info_logged():
    @exceptions.filter(allow_raising=False, summary=Fake("summary"))
    def f():
        raise Exception("some-message")

    with Scenario() as s:
        s.summary.increment_failed()
        s.LOGGER.error(
            "some-message",
            exc_info=True,
            extra={"show_traceback": True}
        )
        assert f() is None

