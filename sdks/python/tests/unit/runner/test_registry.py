from opik.runner.registry import Param, extract_params, get_all, register


class TestRegister:
    def test_register__new_entry__appears_in_registry(self):
        register("agent_a", lambda: None, "proj", [Param("x", "str")], "doc")
        assert "agent_a" in get_all()
        assert get_all()["agent_a"]["project"] == "proj"

    def test_register__duplicate__overwrites(self):
        register("agent_a", lambda: 1, "proj1", [], "doc1")
        register("agent_a", lambda: 2, "proj2", [], "doc2")
        assert get_all()["agent_a"]["project"] == "proj2"

    def test_register__multiple__all_present(self):
        register("a", lambda: None, "p", [], "")
        register("b", lambda: None, "p", [], "")
        assert set(get_all().keys()) == {"a", "b"}


class TestGetAll:
    def test_get_all__empty__returns_empty(self):
        assert get_all() == {}

    def test_get_all__returns_snapshot(self):
        register("a", lambda: None, "p", [], "")
        result = get_all()
        assert result == get_all()
        assert result is not get_all()


class TestExtractParams:
    def test_extract_params__typed(self):
        def fn(x: int, y: str) -> None:
            pass

        params = extract_params(fn)
        assert [(p.name, p.type) for p in params] == [("x", "int"), ("y", "str")]

    def test_extract_params__untyped__defaults_to_str(self):
        def fn(x):
            pass

        params = extract_params(fn)
        assert params[0].type == "str"

    def test_extract_params__no_params(self):
        def fn():
            pass

        assert extract_params(fn) == []
