"""edit_file bridge command handler."""

import difflib
import re
import unicodedata
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from pydantic import BaseModel

from . import BaseHandler, CommandError, FileLockRegistry
from . import common
from .syntax_check import check_syntax


class EditEntry(BaseModel):
    old_string: str
    new_string: str


class EditFileArgs(BaseModel):
    path: str
    edits: list[EditEntry]


def _strip_bom(content: str) -> Tuple[str, str]:
    if content.startswith("\ufeff"):
        return content[1:], "\ufeff"
    return content, ""


def _detect_line_ending(content: str) -> str:
    crlf_idx = content.find("\r\n")
    lf_idx = content.find("\n")
    if lf_idx == -1:
        return "\n"
    if crlf_idx == -1:
        return "\n"
    return "\r\n" if crlf_idx <= lf_idx else "\n"


def _normalize_to_lf(content: str) -> str:
    return content.replace("\r\n", "\n")


def _restore_line_ending(content: str, ending: str) -> str:
    if ending == "\r\n":
        return content.replace("\n", "\r\n")
    return content


_FUZZY_REPLACEMENTS: Dict[str, str] = {
    "\u201c": '"',
    "\u201d": '"',
    "\u2018": "'",
    "\u2019": "'",
    "\u2014": "-",
    "\u2013": "-",
    "\u2212": "-",
    "\u00a0": " ",
    "\u2009": " ",
    "\u200a": " ",
}


def _fuzzy_normalize(text: str) -> str:
    text = unicodedata.normalize("NFKC", text)
    for orig, repl in _FUZZY_REPLACEMENTS.items():
        text = text.replace(orig, repl)
    text = re.sub(r"[ \t]+\n", "\n", text)
    return text


def _fuzzy_normalize_with_map(text: str) -> Tuple[str, List[int]]:
    """Normalize text for fuzzy matching, returning an offset map from
    normalized positions back to original positions."""
    chars: List[str] = []
    offsets: List[int] = []

    for orig_pos, ch in enumerate(text):
        normalized = unicodedata.normalize("NFKC", ch)
        for nc in normalized:
            replacement = _FUZZY_REPLACEMENTS.get(nc, nc)
            chars.append(replacement)
            offsets.append(orig_pos)

    result: List[str] = []
    result_offsets: List[int] = []
    i = 0
    n = len(chars)
    while i < n:
        if chars[i] in (" ", "\t"):
            j = i
            while j < n and chars[j] in (" ", "\t"):
                j += 1
            if j < n and chars[j] == "\n":
                result.append("\n")
                result_offsets.append(offsets[j])
                i = j + 1
            else:
                result.append(chars[i])
                result_offsets.append(offsets[i])
                i += 1
        else:
            result.append(chars[i])
            result_offsets.append(offsets[i])
            i += 1

    return "".join(result), result_offsets


def _map_span_to_original(
    norm_start: int, norm_length: int, offsets: List[int], original_len: int
) -> Tuple[int, int]:
    """Translate a span in normalized text back to original text coordinates."""
    orig_start = offsets[norm_start]
    norm_end = norm_start + norm_length
    if norm_end < len(offsets):
        orig_end = offsets[norm_end]
    else:
        orig_end = original_len
    return orig_start, max(1, orig_end - orig_start)


def _find_exact(content: str, old_string: str) -> Optional[Tuple[int, int]]:
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


def _find_fuzzy(content: str, old_string: str) -> Optional[Tuple[int, int]]:
    norm_old = _fuzzy_normalize(old_string)
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


def _validate_edits(matches: List[Tuple[int, int]]) -> None:
    sorted_matches = sorted(matches, key=lambda m: m[0])
    for i in range(len(sorted_matches) - 1):
        end_a = sorted_matches[i][0] + sorted_matches[i][1]
        start_b = sorted_matches[i + 1][0]
        if end_a > start_b:
            raise CommandError("edits_overlap", "Two edits overlap in the file")


def _apply_edits(content: str, edits: List[Tuple[int, int, str]]) -> str:
    sorted_edits = sorted(edits, key=lambda m: m[0], reverse=True)
    for start, length, new_string in sorted_edits:
        content = content[:start] + new_string + content[start + length :]
    return content


def _generate_diff(old: str, new: str, path: str, context: int = 4) -> str:
    old_lines = old.splitlines(keepends=True)
    new_lines = new.splitlines(keepends=True)
    return "".join(
        difflib.unified_diff(
            old_lines, new_lines, fromfile=f"a/{path}", tofile=f"b/{path}", n=context
        )
    )


class EditFileHandler(BaseHandler):
    def __init__(self, repo_root: Path, mutation_queue: FileLockRegistry) -> None:
        self._repo_root = repo_root
        self._mutation_queue = mutation_queue

    def execute(self, args: Dict[str, Any], timeout: float) -> Dict[str, Any]:
        parsed = EditFileArgs(**args)
        path = common.validate_path(parsed.path, self._repo_root)

        if not parsed.edits:
            raise CommandError("no_change", "No edits provided")

        for edit in parsed.edits:
            if not edit.old_string:
                raise CommandError("match_not_found", "Empty old_string")
            if edit.old_string == edit.new_string:
                raise CommandError("no_change", "old_string equals new_string")

        with self._mutation_queue.lock(path):
            common.revalidate_path(path, self._repo_root)

            if not path.exists():
                raise CommandError("file_not_found", f"File not found: {parsed.path}")
            if not path.is_file():
                raise CommandError("file_not_found", f"Not a file: {parsed.path}")
            if common.is_binary(path):
                raise CommandError("binary_file", f"Binary file: {parsed.path}")

            try:
                raw_content = path.read_bytes().decode("utf-8")
            except UnicodeDecodeError:
                raise CommandError(
                    "binary_file", f"File is not valid UTF-8: {parsed.path}"
                )

            content, bom = _strip_bom(raw_content)
            line_ending = _detect_line_ending(content)
            content_lf = _normalize_to_lf(content)

            matches: List[Tuple[int, int, str]] = []
            all_exact = True
            for edit in parsed.edits:
                old_lf = _normalize_to_lf(edit.old_string)
                result = _find_exact(content_lf, old_lf)
                if result is None:
                    all_exact = False
                    break
                start, length = result
                matches.append((start, length, _normalize_to_lf(edit.new_string)))

            fuzzy_used = False
            if not all_exact:
                matches = []
                fuzzy_content, offset_map = _fuzzy_normalize_with_map(content_lf)
                for edit in parsed.edits:
                    old_lf = _normalize_to_lf(edit.old_string)
                    new_lf = _normalize_to_lf(edit.new_string)
                    result = _find_fuzzy(fuzzy_content, old_lf)
                    if result is None:
                        raise CommandError(
                            "match_not_found", "old_string not found in file"
                        )
                    norm_start, norm_length = result
                    orig_start, orig_length = _map_span_to_original(
                        norm_start, norm_length, offset_map, len(content_lf)
                    )
                    matches.append((orig_start, orig_length, new_lf))
                    fuzzy_used = True

            _validate_edits([(m[0], m[1]) for m in matches])

            new_content = _apply_edits(content_lf, matches)

            new_content = bom + new_content
            new_content = _restore_line_ending(new_content, line_ending)

            rel = str(path.relative_to(self._repo_root))
            diff = _generate_diff(raw_content, new_content, rel)

            try:
                path.write_bytes(new_content.encode("utf-8"))
            except PermissionError:
                raise CommandError(
                    "permission_denied",
                    f"File is not writable: {parsed.path}",
                )

        syntax_result = check_syntax(parsed.path, new_content)

        response: Dict[str, Any] = {
            "diff": diff,
            "edits_applied": len(parsed.edits),
            "fuzzy_match_used": fuzzy_used,
        }
        if syntax_result is not None:
            response["syntax_check"] = syntax_result
        return response
