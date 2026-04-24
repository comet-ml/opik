import logging
from typing import Optional

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
        assert [(p.name, p.type) for p in params] == [("x", "integer"), ("y", "string")]

    def test_extract_params__untyped__defaults_to_str(self):
        def fn(x):
            pass

        params = extract_params(fn)
        assert params[0].type == "string"

    def test_extract_params__no_params(self):
        def fn():
            pass

        assert extract_params(fn) == []

    def test_extract_params__string_annotations__resolved_correctly(self, capture_log):
        """Functions defined with `from __future__ import annotations` have string
        annotations in their __annotations__ dict. extract_params must resolve them
        to actual types so supported primitives don't trigger spurious warnings."""

        # Simulate what `from __future__ import annotations` produces: all annotations
        # are stored as strings rather than live type objects. Mix string-annotated
        # and live-type-annotated params to verify both paths work.
        async def handle_message(
            session_id: str, user_message: str, max_tokens: int
        ) -> str:
            return ""

        handle_message.__annotations__ = {
            "session_id": "str",
            "user_message": "str",
            "max_tokens": "int",
            "return": "str",
        }

        params = extract_params(handle_message)

        assert [(p.name, p.type) for p in params] == [
            ("session_id", "string"),
            ("user_message", "string"),
            ("max_tokens", "integer"),
        ]
        warnings = [r for r in capture_log.records if r.levelno == logging.WARNING]
        assert warnings == [], "No warning expected for standard primitive annotations"

    def test_extract_params__unknown_type__defaults_to_string_and_warns(
        self, capture_log
    ):
        class CustomType:
            pass

        def fn(count: int, first_custom: CustomType, second_custom: CustomType) -> None:
            pass

        params = extract_params(fn)

        assert [(p.name, p.type) for p in params] == [
            ("count", "integer"),
            ("first_custom", "string"),
            ("second_custom", "string"),
        ]
        warnings = [r for r in capture_log.records if r.levelno == logging.WARNING]
        assert len(warnings) == 1
        warning = warnings[0].getMessage()
        assert "first_custom" in warning
        assert "second_custom" in warning

    def test_extract_params__no_default__required(self):
        def fn(x: str) -> None:
            pass

        params = extract_params(fn)
        assert params[0].presence == "required"

    def test_extract_params__with_default__optional(self):
        def fn(x: str = "hi") -> None:
            pass

        params = extract_params(fn)
        assert params[0].presence == "optional"

    def test_extract_params__optional_with_none_default__optional(self):
        def fn(x: Optional[str] = None) -> None:
            pass

        params = extract_params(fn)
        assert params[0].type == "string"
        assert params[0].presence == "optional"

    def test_extract_params__optional_no_default__required(self):
        def fn(x: Optional[str]) -> None:
            pass

        params = extract_params(fn)
        assert params[0].type == "string"
        assert params[0].presence == "required"
