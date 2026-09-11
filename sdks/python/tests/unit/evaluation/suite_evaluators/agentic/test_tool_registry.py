import json

import pytest

from opik.evaluation.suite_evaluators.agentic.tools.registry import ToolRegistry


class _StubTool:
    def __init__(self, name, payload=None, raises=None):
        self.name = name
        self.spec = {"type": "function", "function": {"name": name}}
        self._payload = payload or f"{name}-result"
        self._raises = raises

    def execute(self, arguments, ctx):
        if self._raises is not None:
            raise self._raises
        return self._payload


def test_specs__multiple_tools__returns_in_insertion_order():
    registry = ToolRegistry([_StubTool("alpha"), _StubTool("beta")])
    names = [spec["function"]["name"] for spec in registry.specs()]
    assert names == ["alpha", "beta"]


def test_registry__duplicate_tool_name__rejected():
    with pytest.raises(ValueError):
        ToolRegistry([_StubTool("alpha"), _StubTool("alpha")])


def test_execute__known_tool__returns_tool_payload():
    registry = ToolRegistry([_StubTool("alpha", payload="hi")])
    assert registry.execute("alpha", "{}", ctx=None) == "hi"


def test_execute__unknown_tool__returns_error_json():
    registry = ToolRegistry([_StubTool("alpha")])
    result = json.loads(registry.execute("missing", "{}", ctx=None))
    assert "error" in result and "missing" in result["error"]


def test_execute__tool_raises__swallows_exception_into_error_json():
    registry = ToolRegistry([_StubTool("alpha", raises=RuntimeError("boom"))])
    result = json.loads(registry.execute("alpha", "{}", ctx=None))
    assert "error" in result and "boom" in result["error"]
