"""
Guards the property that makes an event name splittable back into its path: the
levels are joined with `__`, so no single level may contain one.
"""

import ast
import pathlib

import opik
from opik.analytics import api

SOURCE_ROOT = pathlib.Path(opik.__file__).parent


def _instrumented_path_segments():
    """Every literal passed positionally to `track_event` across the SDK."""
    segments = set()

    for path in SOURCE_ROOT.rglob("*.py"):
        if "analytics" in path.parts:
            continue
        try:
            tree = ast.parse(path.read_text())
        except (SyntaxError, UnicodeDecodeError):
            continue

        for node in ast.walk(tree):
            is_track_event = (
                isinstance(node, ast.Call)
                and isinstance(node.func, ast.Attribute)
                and node.func.attr == "track_event"
            )
            if is_track_event:
                segments.update(
                    argument.value
                    for argument in node.args
                    if isinstance(argument, ast.Constant)
                )

    return segments


def test_event_names__no_path_segment_contains_the_separator():
    segments = _instrumented_path_segments()

    assert segments, "found no instrumented call sites - has the AST shape changed?"

    offenders = sorted(s for s in segments if api._LEVEL_SEPARATOR in s)
    assert offenders == [], (
        f"these path segments contain {api._LEVEL_SEPARATOR!r}, so the event names "
        f"they build can no longer be split back into a path: {offenders}"
    )


def test_event_names__every_component_is_separator_free():
    """The closed root vocabulary has to satisfy the same property."""
    components = api.Component.__args__

    assert [c for c in components if api._LEVEL_SEPARATOR in c] == []
