"""
Type annotations for botocore.docs.translator module.

Copyright 2025 Vlad Emelianov
"""

from typing import Any

from sphinx.writers.html5 import HTML5Translator as SphinxHTML5Translator  # type: ignore

class BotoHTML5Translator(SphinxHTML5Translator):
    IGNORE_IMPLICIT_HEADINGS: list[str] = ...
    def visit_admonition(self, node: Any, name: str = ...) -> None: ...
    def is_implicit_heading(self, node: Any) -> bool: ...
    def visit_paragraph(self, node: Any) -> None: ...
