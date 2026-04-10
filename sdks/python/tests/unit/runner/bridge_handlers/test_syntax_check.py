"""Tests for syntax_check module — Python via ast, JS/TS via tree-sitter."""

import pytest

from opik.runner.bridge_handlers.syntax_check import check_syntax


class TestPythonSyntax:
    def test_valid_python__returns_ok(self) -> None:
        code = "def hello():\n    return 42\n"
        assert check_syntax("app.py", code) == "ok"

    def test_syntax_error__returns_diagnostic(self) -> None:
        code = "def hello(\n    return 42\n"
        result = check_syntax("app.py", code)
        assert result is not None
        assert "syntax_error" in result
        assert "line" in result

    def test_indent_error__returns_diagnostic(self) -> None:
        code = "def hello():\nreturn 42\n"
        result = check_syntax("script.py", code)
        assert result is not None
        assert "syntax_error" in result

    def test_empty_file__returns_ok(self) -> None:
        assert check_syntax("empty.py", "") == "ok"

    def test_pyw_extension__checks_python(self) -> None:
        code = "def hello(\n"
        result = check_syntax("script.pyw", code)
        assert result is not None
        assert "syntax_error" in result

    def test_valid_python__complex_file__returns_ok(self) -> None:
        code = (
            "import os\n"
            "from typing import Optional\n\n"
            "class Foo:\n"
            "    def bar(self, x: int) -> Optional[str]:\n"
            "        if x > 0:\n"
            '            return f"value: {x}"\n'
            "        return None\n"
        )
        assert check_syntax("module.py", code) == "ok"


@pytest.mark.skipif(
    not pytest.importorskip("tree_sitter", reason="tree-sitter not installed"),
    reason="tree-sitter not installed",
)
class TestTypescriptSyntax:
    def test_valid_ts__returns_ok(self) -> None:
        code = "function hello(name: string): string {\n    return name;\n}\n"
        result = check_syntax("app.ts", code)
        assert result == "ok"

    def test_ts_syntax_error__returns_diagnostic(self) -> None:
        code = "function hello(name: string {\n    return name;\n}\n"
        result = check_syntax("app.ts", code)
        assert result is not None
        assert "line" in result

    def test_tsx_valid__returns_ok(self) -> None:
        code = "function App(): JSX.Element {\n    return <div>Hello</div>;\n}\n"
        result = check_syntax("App.tsx", code)
        assert result == "ok"

    def test_mts_extension__checks_ts(self) -> None:
        code = "const x: number = ;\n"
        result = check_syntax("module.mts", code)
        assert result is not None
        assert result != "ok"

    def test_cts_extension__checks_ts(self) -> None:
        code = "const x: number = 42;\n"
        assert check_syntax("module.cts", code) == "ok"


@pytest.mark.skipif(
    not pytest.importorskip("tree_sitter", reason="tree-sitter not installed"),
    reason="tree-sitter not installed",
)
class TestJavaScriptSyntax:
    def test_valid_js__returns_ok(self) -> None:
        code = "function hello(name) {\n    return name;\n}\n"
        assert check_syntax("app.js", code) == "ok"

    def test_js_syntax_error__returns_diagnostic(self) -> None:
        code = "function hello(name {\n    return name;\n}\n"
        result = check_syntax("app.js", code)
        assert result is not None
        assert "line" in result

    def test_mjs_extension__checks_js(self) -> None:
        code = "export const x = ;\n"
        result = check_syntax("module.mjs", code)
        assert result is not None
        assert result != "ok"

    def test_cjs_extension__checks_js(self) -> None:
        code = "const x = 42;\n"
        assert check_syntax("module.cjs", code) == "ok"

    def test_jsx_extension__checks_js(self) -> None:
        code = "const App = () => <div>Hello</div>;\n"
        result = check_syntax("App.jsx", code)
        assert result == "ok"


class TestUnsupportedFiles:
    @pytest.mark.parametrize(
        "path",
        [
            "readme.md",
            "config.yaml",
            "data.json",
            "style.css",
            "image.png",
            "noext",
        ],
    )
    def test_unsupported_extension__returns_none(self, path: str) -> None:
        assert check_syntax(path, "some content") is None


class TestTreeSitterUnavailable:
    def test_js_without_tree_sitter__returns_none(self) -> None:
        import opik.runner.bridge_handlers.syntax_check as mod

        old_available = mod._tree_sitter_available
        old_ts = mod._ts_language
        old_tsx = mod._tsx_language
        old_js = mod._js_language
        try:
            mod._tree_sitter_available = False
            mod._ts_language = None
            mod._tsx_language = None
            mod._js_language = None
            result = check_syntax("app.js", "function hello( {")
            assert result is None
        finally:
            mod._tree_sitter_available = old_available
            mod._ts_language = old_ts
            mod._tsx_language = old_tsx
            mod._js_language = old_js

    def test_python_still_works_without_tree_sitter(self) -> None:
        import opik.runner.bridge_handlers.syntax_check as mod

        old_available = mod._tree_sitter_available
        try:
            mod._tree_sitter_available = False
            result = check_syntax("app.py", "def hello(\n")
            assert result is not None
            assert "syntax_error" in result
        finally:
            mod._tree_sitter_available = old_available


class TestHandlerIntegration:
    """Verify syntax_check field in handler responses."""

    def test_edit_file__valid_python__syntax_ok(
        self, tmp_path: "pytest.TempPathFactory"
    ) -> None:
        from opik.runner.bridge_handlers import FileLockRegistry
        from opik.runner.bridge_handlers.edit_file import (
            EditFileHandler,
        )

        f = tmp_path / "code.py"  # type: ignore[operator]
        f.write_text("hello = 'world'\n")
        handler = EditFileHandler(
            tmp_path,
            FileLockRegistry(),  # type: ignore[arg-type]
        )
        result = handler.execute(
            {
                "path": "code.py",
                "edits": [
                    {
                        "old_string": "world",
                        "new_string": "earth",
                    }
                ],
            },
            timeout=30.0,
        )
        assert result["syntax_check"] == "ok"

    def test_edit_file__broken_python__syntax_error(
        self, tmp_path: "pytest.TempPathFactory"
    ) -> None:
        from opik.runner.bridge_handlers import FileLockRegistry
        from opik.runner.bridge_handlers.edit_file import (
            EditFileHandler,
        )

        f = tmp_path / "code.py"  # type: ignore[operator]
        f.write_text("def hello():\n    return 42\n")
        handler = EditFileHandler(
            tmp_path,
            FileLockRegistry(),  # type: ignore[arg-type]
        )
        result = handler.execute(
            {
                "path": "code.py",
                "edits": [
                    {
                        "old_string": "def hello():",
                        "new_string": "def hello(:",
                    }
                ],
            },
            timeout=30.0,
        )
        assert result["syntax_check"] != "ok"
        assert "syntax_error" in result["syntax_check"]

    def test_write_file__valid_python__syntax_ok(
        self, tmp_path: "pytest.TempPathFactory"
    ) -> None:
        from opik.runner.bridge_handlers import FileLockRegistry
        from opik.runner.bridge_handlers.write_file import (
            WriteFileHandler,
        )

        handler = WriteFileHandler(
            tmp_path,
            FileLockRegistry(),  # type: ignore[arg-type]
        )
        result = handler.execute(
            {"path": "new.py", "content": "x = 42\n"},
            timeout=30.0,
        )
        assert result["syntax_check"] == "ok"

    def test_write_file__broken_python__syntax_error(
        self, tmp_path: "pytest.TempPathFactory"
    ) -> None:
        from opik.runner.bridge_handlers import FileLockRegistry
        from opik.runner.bridge_handlers.write_file import (
            WriteFileHandler,
        )

        handler = WriteFileHandler(
            tmp_path,
            FileLockRegistry(),  # type: ignore[arg-type]
        )
        result = handler.execute(
            {"path": "broken.py", "content": "def foo(\n"},
            timeout=30.0,
        )
        assert result["syntax_check"] != "ok"
        assert "syntax_error" in result["syntax_check"]

    def test_write_file__non_source_file__no_syntax_check(
        self, tmp_path: "pytest.TempPathFactory"
    ) -> None:
        from opik.runner.bridge_handlers import FileLockRegistry
        from opik.runner.bridge_handlers.write_file import (
            WriteFileHandler,
        )

        handler = WriteFileHandler(
            tmp_path,
            FileLockRegistry(),  # type: ignore[arg-type]
        )
        result = handler.execute(
            {"path": "readme.md", "content": "# Hello\n"},
            timeout=30.0,
        )
        assert "syntax_check" not in result
