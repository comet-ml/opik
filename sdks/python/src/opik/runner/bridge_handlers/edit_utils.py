"""Edit utilities — BOM, line endings, fuzzy matching, edit application, diff generation."""

import difflib
import unicodedata
from dataclasses import dataclass
from typing import List, Optional, Tuple

from . import CommandError


@dataclass
class MatchResult:
    pos: int
    length: int
    fuzzy: bool


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


_SMART_QUOTES = {
    "\u201c": '"',
    "\u201d": '"',
    "\u2018": "'",
    "\u2019": "'",
}
_DASHES = {
    "\u2014": "-",
    "\u2013": "-",
    "\u2212": "-",
}
_SPACES = {
    "\u00a0": " ",
    "\u2009": " ",
    "\u200a": " ",
}
_CHAR_MAP = {**_SMART_QUOTES, **_DASHES, **_SPACES}


def fuzzy_normalize(text: str) -> str:
    text = unicodedata.normalize("NFKC", text)
    for old, new in _CHAR_MAP.items():
        text = text.replace(old, new)
    lines = text.split("\n")
    lines = [line.rstrip() for line in lines]
    return "\n".join(lines)


def find_match(content: str, old_string: str) -> Optional[MatchResult]:
    count = content.count(old_string)
    if count == 1:
        pos = content.index(old_string)
        return MatchResult(pos=pos, length=len(old_string), fuzzy=False)
    if count > 1:
        raise CommandError("match_ambiguous", f"old_string found {count} times")

    norm_content = fuzzy_normalize(content)
    norm_old = fuzzy_normalize(old_string)

    if not norm_old:
        return None

    count = norm_content.count(norm_old)
    if count == 1:
        norm_pos = norm_content.index(norm_old)
        orig_pos, orig_length = _map_fuzzy_to_original(content, norm_pos, len(norm_old))
        if orig_pos is not None:
            return MatchResult(pos=orig_pos, length=orig_length, fuzzy=True)
    elif count > 1:
        raise CommandError("match_ambiguous", f"old_string found {count} times (fuzzy)")

    return None


def _build_norm_index(content: str) -> List[Tuple[int, int]]:
    """Build a mapping from original char index to normalized char offset.

    Returns a list of (norm_start, norm_len) for each original character.
    norm_start is the cumulative normalized offset, norm_len is how many
    normalized chars this original char produces.
    """
    result = []
    norm_offset = 0
    for ch in content:
        norm_ch = fuzzy_normalize(ch)
        result.append((norm_offset, len(norm_ch)))
        norm_offset += len(norm_ch)
    return result


def _map_fuzzy_to_original(
    content: str, norm_pos: int, norm_length: int
) -> Tuple[Optional[int], int]:
    """Map a position in normalized space back to original string coordinates."""
    index = _build_norm_index(content)
    if not index:
        return None, 0

    orig_start = None
    orig_end = None
    norm_end = norm_pos + norm_length

    for orig_idx, (ns, nl) in enumerate(index):
        if orig_start is None and ns + nl > norm_pos:
            orig_start = orig_idx
        if ns >= norm_end:
            orig_end = orig_idx
            break
    else:
        orig_end = len(content)

    if orig_start is None:
        return None, 0

    return orig_start, orig_end - orig_start


def validate_edits(matches: List[Tuple[MatchResult, str, str]]) -> None:
    for _, old_str, new_str in matches:
        if old_str == new_str:
            raise CommandError("no_change", "old_string and new_string are identical")

    sorted_matches = sorted(matches, key=lambda m: m[0].pos)
    for i in range(len(sorted_matches) - 1):
        m1 = sorted_matches[i][0]
        m2 = sorted_matches[i + 1][0]
        if m1.pos + m1.length > m2.pos:
            raise CommandError("edits_overlap", "Two edits overlap in position")


def apply_edits(content: str, matches: List[Tuple[MatchResult, str]]) -> str:
    sorted_by_pos_desc = sorted(matches, key=lambda m: m[0].pos, reverse=True)
    for match, new_string in sorted_by_pos_desc:
        content = (
            content[: match.pos] + new_string + content[match.pos + match.length :]
        )
    return content


def generate_diff(old: str, new: str, path: str, context: int = 4) -> str:
    old_lines = old.splitlines(keepends=True)
    new_lines = new.splitlines(keepends=True)
    return "".join(
        difflib.unified_diff(
            old_lines, new_lines, fromfile=f"a/{path}", tofile=f"b/{path}", n=context
        )
    )
