"""Regression for OPIK-7849: 12 LOGGER.debug calls in stream/generator error paths
were passing str(exception) as a positional argument to a format string that had no
%s placeholder, so logging's msg % args interpolation raised TypeError and silently
dropped the record.

The test scans the affected source files and asserts that every LOGGER.debug call
in an `except Exception as exception:` block that passes `str(exception)` as a
positional arg has a corresponding `%s` in its format string. The check is
intentionally static (no module import) so it runs without the integration SDKs
(anthropic, openai, etc.) being installed.
"""

import re
from pathlib import Path

import pytest

# (relative-path from sdks/python, expected error-path format string).
# Each entry pins a single LOGGER.debug(...) call site that was fixed. If the
# call site is renamed or moved, the regression test fails loudly so the next
# person can update both the source and the test in one change.
AFFECTED_CALL_SITES = [
    (
        "src/opik/decorator/generator_wrappers.py",
        "Exception raised from tracked generator: %s",
    ),
    (
        "src/opik/integrations/anthropic/stream_patchers.py",
        "Exception raised from anthropic.Stream: %s",
    ),
    (
        "src/opik/integrations/anthropic/stream_patchers.py",
        "Exception raised from anthropic.AsyncStream: %s",
    ),
    (
        "src/opik/integrations/anthropic/stream_patchers.py",
        "Exception raised from anthropic.MessageStream: %s",
    ),
    (
        "src/opik/integrations/anthropic/stream_patchers.py",
        "Exception raised from anthropic.AsyncMessageStream: %s",
    ),
    (
        "src/opik/integrations/bedrock/converse/stream_wrappers.py",
        "Exception raised from botocore.eventstream.EventStream: %s",
    ),
    (
        "src/opik/integrations/genai/stream_wrappers.py",
        "Exception raised in genai response stream: %s",
    ),
    (
        "src/opik/integrations/groq/stream_patchers.py",
        "Exception raised from groq.Stream: %s",
    ),
    (
        "src/opik/integrations/groq/stream_patchers.py",
        "Exception raised from groq.AsyncStream: %s",
    ),
    (
        "src/opik/integrations/openai/stream_patchers.py",
        "Exception raised from openai.Stream: %s",
    ),
    (
        "src/opik/integrations/openai/stream_patchers.py",
        "Exception raised from openai.AsyncStream: %s",
    ),
]

# Pattern that captures a LOGGER.debug call passing str(exception) as a positional
# argument to a format string. The captured group is the format string; the regex
# is non-greedy on the string literal so it stops at the first closing quote.
LOGGER_DEBUG_WITH_EXCEPTION_PATTERN = re.compile(
    r'LOGGER\.debug\(\s*"([^"]*)"\s*,\s*str\(\s*exception\s*\)',
    re.DOTALL,
)


def _scan_log_format_strings(relative_path: str) -> list[tuple[str, int]]:
    """Return [(format_string, line_number), ...] for every LOGGER.debug that
    passes str(exception) as a positional argument, anywhere in the file.

    Some call sites are inside a helper method that an `except` block calls (e.g.
    `generator_wrappers.py:_handle_generator_exception_before_raising`); others are
    inline in the `except` block itself. Scanning the whole file covers both.
    """
    source_path = Path(__file__).resolve().parents[3] / relative_path
    with open(source_path, encoding="utf-8") as fp:
        source = fp.read()
    return [
        (match.group(1), source[: match.start()].count("\n") + 1)
        for match in LOGGER_DEBUG_WITH_EXCEPTION_PATTERN.finditer(source)
    ]


@pytest.mark.parametrize(
    ("relative_path", "expected_format"),
    AFFECTED_CALL_SITES,
    ids=[f"{p}::{expected}" for p, expected in AFFECTED_CALL_SITES],
)
def test_log_format_string_has_placeholder_for_exception(
    relative_path: str, expected_format: str
) -> None:
    """Pin the exact format string used by each fixed call site. If a future
    refactor drops the %s, this test fails."""
    found = _scan_log_format_strings(relative_path)
    matching = [fmt for fmt, _ in found if fmt == expected_format]
    assert matching, (
        f"{relative_path}: expected format string {expected_format!r} not found. "
        f"Found: {[fmt for fmt, _ in found]!r}. If the call site was renamed, "
        "update both the source and this test in the same change."
    )


def test_no_remaining_broken_log_calls() -> None:
    """Sanity: every LOGGER.debug(...) call in an except block that passes
    str(exception) as a positional arg has a %s in its format string. This
    catches new call sites that follow the same pattern but are not in the
    fixed list yet."""
    for relative_path, _ in AFFECTED_CALL_SITES:
        for fmt, line_number in _scan_log_format_strings(relative_path):
            assert "%s" in fmt, (
                f"{relative_path}:{line_number}: format string {fmt!r} is passed "
                "str(exception) but has no %s placeholder. With debug logging "
                "enabled, logging's msg % args interpolation raises TypeError and "
                "silently drops the record (see OPIK-7849)."
            )
