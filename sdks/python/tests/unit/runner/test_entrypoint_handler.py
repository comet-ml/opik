import io
import json
import sys

import pytest

from opik.runner import agents_registry, entrypoint_handler


@pytest.fixture(autouse=True)
def no_dispatch(monkeypatch):
    monkeypatch.delenv("OPIK_RUNNER_DISPATCH", raising=False)
    monkeypatch.delenv("OPIK_AGENT", raising=False)


class TestParamExtraction:
    def test_extracts_typed_params(self):
        def my_func(query: str, count: int, ratio: float) -> str:
            return ""

        entrypoint_handler.handle_entrypoint(my_func, name="my_func")
        agents = agents_registry.load_agents()
        params = agents["my_func"].params
        assert [(p.name, p.type) for p in params] == [
            ("query", "str"),
            ("count", "int"),
            ("ratio", "float"),
        ]

    def test_untyped_params_default_to_str(self):
        def my_func(query):
            return ""

        entrypoint_handler.handle_entrypoint(my_func, name="my_func")
        agents = agents_registry.load_agents()
        assert agents["my_func"].params[0].type == "str"

    def test_bool_dict_list_none_types(self):
        def my_func(flag: bool, data: dict, items: list) -> None:
            pass

        entrypoint_handler.handle_entrypoint(my_func, name="my_func")
        agents = agents_registry.load_agents()
        types = [p.type for p in agents["my_func"].params]
        assert types == ["bool", "dict", "list"]

    def test_rejects_positional_only(self):
        code = "def my_func(x, /, y): pass"
        ns = {}
        exec(code, ns)
        func = ns["my_func"]

        with pytest.raises(TypeError, match="Positional-only"):
            entrypoint_handler.handle_entrypoint(func, name="my_func")

    def test_rejects_var_positional(self):
        def my_func(query: str, *args) -> str:
            return ""

        with pytest.raises(TypeError, match=r"\*args is not supported"):
            entrypoint_handler.handle_entrypoint(my_func, name="my_func")

    def test_skips_var_keyword(self):
        def my_func(query: str, **kwargs) -> str:
            return ""

        entrypoint_handler.handle_entrypoint(my_func, name="my_func")
        agents = agents_registry.load_agents()
        params = agents["my_func"].params
        assert [(p.name, p.type) for p in params] == [("query", "str")]

    def test_unwraps_generic_list(self):
        from typing import List

        def my_func(items: List[str]) -> None:
            pass

        entrypoint_handler.handle_entrypoint(my_func, name="my_func")
        agents = agents_registry.load_agents()
        assert agents["my_func"].params[0].type == "list"

    def test_unwraps_builtin_generic(self):
        def my_func(items: list[str], data: dict[str, int]) -> None:
            pass

        entrypoint_handler.handle_entrypoint(my_func, name="my_func")
        agents = agents_registry.load_agents()
        types = [p.type for p in agents["my_func"].params]
        assert types == ["list", "dict"]

    def test_unwraps_optional_to_inner_type(self):
        from typing import Optional

        def my_func(name: Optional[str], count: Optional[int]) -> None:
            pass

        entrypoint_handler.handle_entrypoint(my_func, name="my_func")
        agents = agents_registry.load_agents()
        types = [p.type for p in agents["my_func"].params]
        assert types == ["str", "int"]

    def test_rejects_unsupported_type(self):
        import datetime

        def my_func(ts: datetime.datetime) -> None:
            pass

        with pytest.raises(TypeError, match="unsupported type"):
            entrypoint_handler.handle_entrypoint(my_func, name="my_func")


class TestRegistration:
    def test_registers_agent_info(self):
        def agent_fn(query: str) -> str:
            """An agent that answers questions."""
            return ""

        entrypoint_handler.handle_entrypoint(agent_fn, name="my_agent")
        agents = agents_registry.load_agents()
        agent = agents["my_agent"]

        assert agent.name == "my_agent"
        assert agent.executable == sys.executable
        assert agent.description == "An agent that answers questions."
        assert agent.language == "python"

    def test_registers_empty_description_when_no_docstring(self):
        def agent_fn(query: str) -> str:
            return ""

        entrypoint_handler.handle_entrypoint(agent_fn, name="my_agent")
        agents = agents_registry.load_agents()
        assert agents["my_agent"].description == ""

    def test_registers_timeout_when_provided(self):
        def agent_fn(query: str) -> str:
            return ""

        entrypoint_handler.handle_entrypoint(agent_fn, name="my_agent", timeout=30)
        agents = agents_registry.load_agents()
        assert agents["my_agent"].timeout == 30

    def test_registers_project_when_provided(self):
        def agent_fn(query: str) -> str:
            return ""

        entrypoint_handler.handle_entrypoint(
            agent_fn, name="my_agent", project_name="my-project"
        )
        agents = agents_registry.load_agents()
        assert agents["my_agent"].project == "my-project"

    def test_no_project_when_not_provided(self):
        def agent_fn(query: str) -> str:
            return ""

        entrypoint_handler.handle_entrypoint(agent_fn, name="my_agent")
        agents = agents_registry.load_agents()
        assert agents["my_agent"].project is None


class TestDispatch:
    def test_dispatch_calls_func_and_exits(self, monkeypatch, tmp_path):
        monkeypatch.setenv("OPIK_RUNNER_DISPATCH", "1")
        monkeypatch.setenv("OPIK_AGENT", "my_agent")
        result_file = str(tmp_path / "result.json")
        monkeypatch.setenv("OPIK_RESULT_FILE", result_file)
        monkeypatch.setattr("sys.stdin", io.StringIO(json.dumps({"query": "hi"})))

        def agent_fn(query: str) -> str:
            return f"response to {query}"

        with pytest.raises(SystemExit) as exc_info:
            entrypoint_handler.handle_entrypoint(agent_fn, name="my_agent")

        assert exc_info.value.code == 0

        with open(result_file) as f:
            data = json.load(f)
        assert data["result"] == "response to hi"

    def test_no_dispatch_when_env_not_set(self):
        def agent_fn(query: str) -> str:
            return ""

        result = entrypoint_handler.handle_entrypoint(agent_fn, name="my_agent")
        assert result is agent_fn

    def test_no_dispatch_when_agent_name_differs(self, monkeypatch):
        monkeypatch.setenv("OPIK_RUNNER_DISPATCH", "1")
        monkeypatch.setenv("OPIK_AGENT", "other_agent")

        def agent_fn(query: str) -> str:
            return ""

        result = entrypoint_handler.handle_entrypoint(agent_fn, name="my_agent")
        assert result is agent_fn

    def test_returns_func_unchanged(self):
        def agent_fn(query: str) -> str:
            return "hello"

        result = entrypoint_handler.handle_entrypoint(agent_fn, name="my_agent")
        assert result("test") == "hello"
