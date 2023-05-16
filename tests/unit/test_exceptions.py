import pytest

from comet_llm import exceptions


def test_reraiser_no_exceptions():
    @exceptions.reraiser(to_raise=ValueError, to_catch=ZeroDivisionError)
    def f():
        return "return-value"

    assert f() == "return-value"


def test_reraiser__exception_reraised():
    @exceptions.reraiser(to_raise=ValueError, to_catch=ZeroDivisionError)
    def f():
        1/0
        return "return-value"

    with pytest.raises(ValueError):
        f()


