"""Post-edit syntax validation for source files.

Uses ast.parse for Python and tree-sitter for TypeScript/JavaScript.
tree-sitter is an optional dependency — if not installed, JS/TS/TSX/JSX
files are not checked and check_syntax returns None for those extensions.
"""

import ast
import logging
from typing import Any, Optional

logger = logging.getLogger(__name__)

_PYTHON_EXTENSIONS = frozenset({".py", ".pyw"})
_TS_EXTENSIONS = frozenset({".ts", ".mts", ".cts"})
_TSX_EXTENSIONS = frozenset({".tsx"})
_JS_EXTENSIONS = frozenset({".js", ".mjs", ".cjs", ".jsx"})

_tree_sitter_available: Optional[bool] = None
_ts_language: object = None
_tsx_language: object = None
_js_language: object = None


def _ensure_tree_sitter() -> bool:
    """Lazily import tree-sitter and cache Language objects.

    Returns True if tree-sitter is available. Language objects are
    thread-safe and immutable; Parser instances are created per-call
    in _check_tree_sitter to avoid shared mutable state.
    """
    global _tree_sitter_available, _ts_language, _tsx_language, _js_language

    if _tree_sitter_available is not None:
        return _tree_sitter_available

    try:
        import tree_sitter
        import tree_sitter_javascript
        import tree_sitter_typescript

        _ts_language = tree_sitter.Language(
            tree_sitter_typescript.language_typescript()
        )
        _tsx_language = tree_sitter.Language(tree_sitter_typescript.language_tsx())
        _js_language = tree_sitter.Language(tree_sitter_javascript.language())
        _tree_sitter_available = True
    except ImportError:
        logger.debug("tree-sitter not installed, JS/TS syntax checking disabled")
        _tree_sitter_available = False

    return _tree_sitter_available


def _find_tree_sitter_errors(
    root_node: Any,
) -> list[tuple[int, int, str]]:
    """Walk the tree-sitter AST and collect ERROR / MISSING nodes."""
    errors: list[tuple[int, int, str]] = []
    stack = [root_node]
    while stack:
        node = stack.pop()
        if node.type == "ERROR":  # type: ignore[attr-defined]
            row, col = node.start_point  # type: ignore[attr-defined]
            errors.append((row + 1, col, "ERROR"))
        elif node.is_missing:  # type: ignore[attr-defined]
            row, col = node.start_point  # type: ignore[attr-defined]
            errors.append((row + 1, col, "MISSING"))
        else:
            stack.extend(node.children)  # type: ignore[attr-defined]
    return errors


def check_syntax(path_str: str, content: str) -> Optional[str]:
    """Validate syntax of a source file after editing.

    Args:
        path_str: File path (used to determine language from extension).
        content: The full file content to check.

    Returns:
        None if the file type is unsupported or tree-sitter is not
        installed for JS/TS files. A string ``"ok"`` if syntax is
        valid. A diagnostic string like
        ``"syntax_error: line 5, col 12: unexpected indent"``
        if a syntax error is found.
    """
    suffix = _get_suffix(path_str)

    if suffix in _PYTHON_EXTENSIONS:
        return _check_python(content, path_str)

    if suffix in _TS_EXTENSIONS:
        return _check_tree_sitter(content, _get_ts_language)

    if suffix in _TSX_EXTENSIONS:
        return _check_tree_sitter(content, _get_tsx_language)

    if suffix in _JS_EXTENSIONS:
        return _check_tree_sitter(content, _get_js_language)

    return None


def _get_suffix(path_str: str) -> str:
    """Extract lowercase file extension from path."""
    dot = path_str.rfind(".")
    if dot == -1:
        return ""
    return path_str[dot:].lower()


def _check_python(content: str, path_str: str) -> Optional[str]:
    """Check Python syntax using ast.parse.

    Returns ``"ok"`` on success, a diagnostic string on error.
    """
    try:
        ast.parse(content, filename=path_str)
        return "ok"
    except SyntaxError as e:
        line = e.lineno or 0
        col = (e.offset or 1) - 1
        msg = e.msg
        return f"syntax_error: line {line}, col {col}: {msg}"


def _get_ts_language() -> Optional[object]:
    if _ensure_tree_sitter():
        return _ts_language
    return None


def _get_tsx_language() -> Optional[object]:
    if _ensure_tree_sitter():
        return _tsx_language
    return None


def _get_js_language() -> Optional[object]:
    if _ensure_tree_sitter():
        return _js_language
    return None


def _check_tree_sitter(
    content: str,
    language_getter: object,
) -> Optional[str]:
    """Check JS/TS syntax using tree-sitter.

    Creates a fresh Parser per call for thread safety. Returns None
    if tree-sitter is not installed, ``"ok"`` if valid, or a
    diagnostic string on error.
    """
    language = language_getter()  # type: ignore[operator]
    if language is None:
        return None

    import tree_sitter

    parser = tree_sitter.Parser(language)
    tree = parser.parse(content.encode("utf-8"))
    root = tree.root_node

    if not root.has_error:
        return "ok"

    errors = _find_tree_sitter_errors(root)
    if not errors:
        return "ok"

    line, col, node_type = errors[0]
    label = "missing_node" if node_type == "MISSING" else "syntax_error"
    return f"{label}: line {line}, col {col}"
