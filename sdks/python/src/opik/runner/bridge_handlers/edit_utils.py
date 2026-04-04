"""Edit utilities — BOM, line endings, fuzzy matching, edit application, diff generation."""

import difflib
import re
import unicodedata
from typing import List, Optional, Tuple

from . import CommandError


def strip_bom(content: str) -> Tuple[str, str]:
    if content.startswith("\ufeff"):
        return content[1:], "\ufeff"
    return content, ""


def detect_line_ending(content: str) -> str:
    crlf_idx = content.find("\r\n")
    lf_idx = content.find("\n")
    if lf_idx == -1:
        return "\n"
    if crlf_idx == -1:
        return "\n"
    return "\r\n" if crlf_idx <= lf_idx else "\n"


def normalize_to_lf(content: str) -> str:
    return content.replace("\r\n", "\n")


def restore_line_ending(content: str, ending: str) -> str:
    if ending == "\r\n":
        return content.replace("\n", "\r\n")
    return content


def fuzzy_normalize(text: str) -> str:
    text = unicodedata.normalize("NFKC", text)
    text = text.replace("\u201c", '"').replace("\u201d", '"')
    text = text.replace("\u2018", "'").replace("\u2019", "'")
    text = text.replace("\u2014", "-").replace("\u2013", "-").replace("\u2212", "-")
    text = text.replace("\u00a0", " ").replace("\u2009", " ").replace("\u200a", " ")
    text = re.sub(r"[ \t]+\n", "\n", text)
    return text


def find_exact(content: str, old_string: str) -> Optional[Tuple[int, int]]:
    """Find exact match. Returns (start, length) or None. Raises on ambiguous."""
    first = content.find(old_string)
    if first == -1:
        return None
    second = content.find(old_string, first + 1)
    if second != -1:
        count = content.count(old_string)
        raise CommandError(
            "match_ambiguous", f"Found {count} matches for the search string"
        )
    return (first, len(old_string))


def find_fuzzy(content: str, old_string: str) -> Optional[Tuple[int, int]]:
    """Find fuzzy match in already-normalized content. Returns (start, length) or None."""
    norm_old = fuzzy_normalize(old_string)
    first = content.find(norm_old)
    if first == -1:
        return None
    second = content.find(norm_old, first + 1)
    if second != -1:
        count = content.count(norm_old)
        raise CommandError(
            "match_ambiguous", f"Found {count} fuzzy matches for the search string"
        )
    return (first, len(norm_old))


def validate_edits(matches: List[Tuple[int, int]]) -> None:
    """Validate (start, length) pairs don't overlap."""
    sorted_matches = sorted(matches, key=lambda m: m[0])
    for i in range(len(sorted_matches) - 1):
        end_a = sorted_matches[i][0] + sorted_matches[i][1]
        start_b = sorted_matches[i + 1][0]
        if end_a > start_b:
            raise CommandError("edits_overlap", "Two edits overlap in the file")


def apply_edits(content: str, edits: List[Tuple[int, int, str]]) -> str:
    """Apply edits in reverse order. Each edit is (start, length, new_string)."""
    sorted_edits = sorted(edits, key=lambda m: m[0], reverse=True)
    for start, length, new_string in sorted_edits:
        content = content[:start] + new_string + content[start + length :]
    return content


def generate_diff(old: str, new: str, path: str, context: int = 4) -> str:
    old_lines = old.splitlines(keepends=True)
    new_lines = new.splitlines(keepends=True)
    return "".join(
        difflib.unified_diff(
            old_lines, new_lines, fromfile=f"a/{path}", tofile=f"b/{path}", n=context
        )
    )
