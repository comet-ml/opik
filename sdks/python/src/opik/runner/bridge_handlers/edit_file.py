"""edit_file bridge command handler."""

import difflib
import re
import unicodedata
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from pydantic import BaseModel

from . import BaseHandler, CommandError, FileMutationQueue
from . import common


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


def _fuzzy_normalize(text: str) -> str:
    text = unicodedata.normalize("NFKC", text)
    text = text.replace("\u201c", '"').replace("\u201d", '"')
    text = text.replace("\u2018", "'").replace("\u2019", "'")
    text = text.replace("\u2014", "-").replace("\u2013", "-").replace("\u2212", "-")
    text = text.replace("\u00a0", " ").replace("\u2009", " ").replace("\u200a", " ")
    text = re.sub(r"[ \t]+\n", "\n", text)
    return text


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
    def __init__(self, repo_root: Path, mutation_queue: FileMutationQueue) -> None:
        self._repo_root = repo_root
        self._mutation_queue = mutation_queue

    def execute(self, args: Dict[str, Any], timeout: float) -> Dict[str, Any]:
        parsed = EditFileArgs(**args)
        path, _ = common.resolve_text_file(parsed.path, self._repo_root)

        if not parsed.edits:
            raise CommandError("no_change", "No edits provided")

        for edit in parsed.edits:
            if not edit.old_string:
                raise CommandError("match_not_found", "Empty old_string")
            if edit.old_string == edit.new_string:
                raise CommandError("no_change", "old_string equals new_string")

        with self._mutation_queue.lock(path):
            common.revalidate_path(path, self._repo_root)

            raw_content = path.read_bytes().decode("utf-8")

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
                fuzzy_content = _fuzzy_normalize(content_lf)
                for edit in parsed.edits:
                    old_lf = _normalize_to_lf(edit.old_string)
                    new_lf = _normalize_to_lf(edit.new_string)
                    result = _find_fuzzy(fuzzy_content, old_lf)
                    if result is None:
                        raise CommandError(
                            "match_not_found", "old_string not found in file"
                        )
                    start, length = result
                    matches.append((start, length, new_lf))
                    fuzzy_used = True

            _validate_edits([(m[0], m[1]) for m in matches])

            target = _fuzzy_normalize(content_lf) if fuzzy_used else content_lf
            new_content = _apply_edits(target, matches)

            new_content = bom + new_content
            new_content = _restore_line_ending(new_content, line_ending)

            rel = str(path.relative_to(self._repo_root))
            diff = _generate_diff(raw_content, new_content, rel)

            path.write_bytes(new_content.encode("utf-8"))

        return {
            "diff": diff,
            "edits_applied": len(parsed.edits),
            "fuzzy_match_used": fuzzy_used,
        }
