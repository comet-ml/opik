"""Post-edit syntax validation for source files.

Uses ast.parse for Python and tree-sitter for TypeScript/JavaScript.
Returns a short diagnostic string or None if the file is syntactically valid.
"""

import ast
import logging
from typing import Optional

logger = logging.getLogger(__name__)

_PYTHON_EXTENSIONS = frozenset({".py", ".pyw"})
_TS_EXTENSIONS = frozenset({".ts", ".mts", ".cts"})
_TSX_EXTENSIONS = frozenset({".tsx"})
_JS_EXTENSIONS = frozenset({".js", ".mjs", ".cjs", ".jsx"})

_tree_sitter_available: Optional[bool] = None
_ts_parser: object = None
_tsx_parser: object = None
_js_parser: object = None


def _ensure_tree_sitter() -> bool:
    """Lazily import tree-sitter and create parsers. Returns True if available."""
    global _tree_sitter_available, _ts_parser, _tsx_parser, _js_parser

    if _tree_sitter_available is not None:
        return _tree_sitter_available

    try:
        import tree_sitter
        import tree_sitter_javascript
        import tree_sitter_typescript

        _ts_parser = tree_sitter.Parser(
            tree_sitter.Language(tree_sitter_typescript.language_typescript())
        )
        _tsx_parser = tree_sitter.Parser(
            tree_sitter.Language(tree_sitter_typescript.language_tsx())
        )
        _js_parser = tree_sitter.Parser(
            tree_sitter.Language(tree_sitter_javascript.language())
        )
        _tree_sitter_available = True
    except ImportError:
        logger.debug("tree-sitter not installed, JS/TS syntax checking disabled")
        _tree_sitter_available = False

    return _tree_sitter_available


def _find_tree_sitter_errors(root_node: object) -> list[tuple[int, int, str]]:
    """Walk the tree-sitter AST and collect ERROR / MISSING nodes."""
    errors: list[tuple[int, int, str]] = []
    stack = [root_node]
    while stack:
        node = stack.pop()
        if node.type == "ERROR":  # type: ignore[union-attr]
            row, col = node.start_point  # type: ignore[union-attr]
            errors.append((row + 1, col, "ERROR"))
        elif node.is_missing:  # type: ignore[union-attr]
            row, col = node.start_point  # type: ignore[union-attr]
            errors.append((row + 1, col, "MISSING"))
        else:
            stack.extend(node.children)  # type: ignore[union-attr]
    return errors


def check_syntax(path_str: str, content: str) -> Optional[str]:
    """Validate syntax of a source file after editing.

    Args:
        path_str: File path (used to determine language from extension).
        content: The full file content to check.

    Returns:
        None if syntax is valid or the file type is not supported.
        A diagnostic string like "syntax_error: line 5, col 12: unexpected indent"
        if a syntax error is found.
    """
    suffix = _get_suffix(path_str)

    if suffix in _PYTHON_EXTENSIONS:
        return _check_python(content, path_str)

    if suffix in _TS_EXTENSIONS:
        return _check_tree_sitter(content, _get_ts_parser)

    if suffix in _TSX_EXTENSIONS:
        return _check_tree_sitter(content, _get_tsx_parser)

    if suffix in _JS_EXTENSIONS:
        return _check_tree_sitter(content, _get_js_parser)

    return None


def _get_suffix(path_str: str) -> str:
    """Extract lowercase file extension from path."""
    dot = path_str.rfind(".")
    if dot == -1:
        return ""
    return path_str[dot:].lower()


def _check_python(content: str, path_str: str) -> Optional[str]:
    """Check Python syntax using ast.parse."""
    try:
        ast.parse(content, filename=path_str)
        return None
    except SyntaxError as e:
        line = e.lineno or 0
        col = (e.offset or 1) - 1
        msg = e.msg
        return f"syntax_error: line {line}, col {col}: {msg}"


def _get_ts_parser() -> Optional[object]:
    if _ensure_tree_sitter():
        return _ts_parser
    return None


def _get_tsx_parser() -> Optional[object]:
    if _ensure_tree_sitter():
        return _tsx_parser
    return None


def _get_js_parser() -> Optional[object]:
    if _ensure_tree_sitter():
        return _js_parser
    return None


def _check_tree_sitter(
    content: str,
    parser_getter: object,
) -> Optional[str]:
    """Check JS/TS syntax using tree-sitter."""
    parser = parser_getter()  # type: ignore[operator]
    if parser is None:
        return None

    tree = parser.parse(content.encode("utf-8"))  # type: ignore[union-attr]
    root = tree.root_node

    if not root.has_error:
        return None

    errors = _find_tree_sitter_errors(root)
    if not errors:
        return None

    line, col, node_type = errors[0]
    label = "missing_node" if node_type == "MISSING" else "syntax_error"
    return f"{label}: line {line}, col {col}"
