"""Each optimizer's documentation lives in its real class docstring.

A string placed after class attributes is not a docstring: help() and the
generated API reference then show no parameter descriptions.
"""

from __future__ import annotations

import inspect

import pytest

from tests.unit.characterization import harness


@pytest.mark.parametrize("optimizer_name", harness.OPTIMIZERS)
def test_optimizer_documents_its_parameters(optimizer_name: str) -> None:
    optimizer_cls = type(harness.make_optimizer(optimizer_name))

    assert "Args:" in (inspect.getdoc(optimizer_cls) or "")
