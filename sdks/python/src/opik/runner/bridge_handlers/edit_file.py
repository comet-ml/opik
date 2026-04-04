"""edit_file bridge command handler."""

import os
from pathlib import Path
from typing import Any, Dict, List, Tuple

from . import CommandError, FileMutationQueue
from .edit_utils import (
    MatchResult,
    apply_edits,
    find_match,
    generate_diff,
    normalize_to_lf,
    restore_line_ending,
    strip_bom,
    detect_line_ending,
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
            if not edit.get("old_string"):
                raise CommandError("match_not_found", "Empty old_string")

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
            normalized = normalize_to_lf(content)

            matches: List[Tuple[MatchResult, str, str]] = []
            for edit in edits:
                old_str = edit["old_string"]
                new_str = edit["new_string"]
                match = find_match(normalized, old_str)
                if match is None:
                    raise CommandError(
                        "match_not_found", "old_string not found in file"
                    )
                matches.append((match, old_str, new_str))

            validate_edits(matches)

            apply_pairs = [(m, new_str) for m, _, new_str in matches]
            result_content = apply_edits(normalized, apply_pairs)

            result_content = bom + result_content
            result_content = restore_line_ending(result_content, line_ending)

            rel = str(path.relative_to(self._repo_root))
            diff = generate_diff(raw_content, result_content, rel)

            path.write_bytes(result_content.encode("utf-8"))

        return {
            "diff": diff,
            "edits_applied": len(edits),
            "fuzzy_match_used": any(m.fuzzy for m, _, _ in matches),
        }


def _revalidate_path(path: Path, repo_root: Path) -> None:
    real = os.path.realpath(path)
    real_root = os.path.realpath(repo_root)
    if not real.startswith(real_root + os.sep) and real != real_root:
        raise CommandError("path_traversal", "Path changed to point outside repository")
