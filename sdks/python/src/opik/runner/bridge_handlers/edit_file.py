"""edit_file bridge command handler."""

import os
from pathlib import Path
from typing import Any, Dict, List, Tuple

from . import CommandError, FileMutationQueue
from .edit_utils import (
    apply_edits,
    detect_line_ending,
    find_exact,
    find_fuzzy,
    fuzzy_normalize,
    generate_diff,
    normalize_to_lf,
    restore_line_ending,
    strip_bom,
    validate_edits,
)
from .path_utils import is_binary, validate_path


class EditFileHandler:
    def __init__(self, repo_root: Path, mutation_queue: FileMutationQueue) -> None:
        self._repo_root = repo_root
        self._mutation_queue = mutation_queue

    def execute(self, args: Dict[str, Any], timeout: float) -> Dict[str, Any]:
        path = validate_path(args.get("path", ""), self._repo_root)

        if not path.exists():
            raise CommandError(
                "file_not_found", f"File not found: {args.get('path', '')}"
            )

        if is_binary(path):
            raise CommandError("binary_file", f"Binary file: {args.get('path', '')}")

        edits: List[Dict[str, str]] = args.get("edits", [])
        if not edits:
            raise CommandError("no_change", "No edits provided")

        for edit in edits:
            old = edit.get("old_string", "")
            new = edit.get("new_string", "")
            if not old:
                raise CommandError("match_not_found", "Empty old_string")
            if old == new:
                raise CommandError("no_change", "old_string equals new_string")

        with self._mutation_queue.lock(path):
            _revalidate_path(path, self._repo_root)

            try:
                raw_content = path.read_bytes().decode("utf-8")
            except UnicodeDecodeError:
                raise CommandError(
                    "binary_file", f"File is not valid UTF-8: {args.get('path', '')}"
                )

            content, bom = strip_bom(raw_content)
            line_ending = detect_line_ending(content)
            content_lf = normalize_to_lf(content)

            # Tier 1: exact matching on LF-normalized content
            matches: List[Tuple[int, int, str]] = []
            all_exact = True
            for edit in edits:
                old_lf = normalize_to_lf(edit["old_string"])
                result = find_exact(content_lf, old_lf)
                if result is None:
                    all_exact = False
                    break
                start, length = result
                matches.append((start, length, normalize_to_lf(edit["new_string"])))

            # Tier 2: fuzzy matching on normalized content if any exact match failed
            fuzzy_used = False
            if not all_exact:
                matches = []
                fuzzy_content = fuzzy_normalize(content_lf)
                for edit in edits:
                    old_lf = normalize_to_lf(edit["old_string"])
                    new_lf = normalize_to_lf(edit["new_string"])
                    result = find_fuzzy(fuzzy_content, old_lf)
                    if result is None:
                        raise CommandError(
                            "match_not_found", "old_string not found in file"
                        )
                    start, length = result
                    matches.append((start, length, new_lf))
                    fuzzy_used = True

            validate_edits([(m[0], m[1]) for m in matches])

            if fuzzy_used:
                new_content = apply_edits(fuzzy_normalize(content_lf), matches)
            else:
                new_content = apply_edits(content_lf, matches)

            new_content = bom + new_content
            new_content = restore_line_ending(new_content, line_ending)

            rel = str(path.relative_to(self._repo_root))
            diff = generate_diff(raw_content, new_content, rel)

            path.write_bytes(new_content.encode("utf-8"))

        return {
            "diff": diff,
            "edits_applied": len(edits),
            "fuzzy_match_used": fuzzy_used,
        }


def _revalidate_path(path: Path, repo_root: Path) -> None:
    real = os.path.realpath(path)
    real_root = os.path.realpath(repo_root)
    if not real.startswith(real_root + os.sep) and real != real_root:
        raise CommandError("path_traversal", "Path changed to point outside repository")
