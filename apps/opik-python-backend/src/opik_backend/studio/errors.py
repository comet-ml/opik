"""Maps optimization failures to high-level, user-facing messages (OPIK-7159).

The optimizer worker is the right place to translate a failure into something a
user should read: it has the real exception object (typed Studio errors, plus
provider SDK exceptions like litellm/openai), so it can classify by type instead
of guessing from log strings in the browser. The frontend just displays what we
produce here; the full traceback stays in the logs ("View logs").

Keep every message HIGH-LEVEL and actionable — never leak a traceback, a provider
SDK class name, or an internal stack frame into the returned text.
"""

import re

from .exceptions import (
    DatasetNotFoundError,
    EmptyDatasetError,
    InvalidConfigError,
    InvalidMetricError,
    InvalidOptimizerError,
    JobMessageParseError,
    OptimizationError,
)

GENERIC_USER_MESSAGE = (
    "The optimization run ran into an unexpected error and stopped. "
    "Open the logs for the full details."
)

# Conservative, high-signal patterns matched against "<ExceptionType> <message>".
# First match wins. Anything not clearly recognized stays generic — we would
# rather show a clean generic message than surface a low-level detail.
_CATEGORIES = [
    (
        re.compile(r"rate.?limit|\b429\b|too many requests|quota", re.I),
        "The model provider rate-limited this run. Wait a little and try running it again.",
    ),
    (
        re.compile(
            r"\b401\b|\b403\b|unauthori|authentication|invalid[\s_-]?api[\s_-]?key|\bapi key\b|forbidden|permission denied|credential",
            re.I,
        ),
        "The run couldn't authenticate with the model provider. Check the API key and its permissions.",
    ),
    (
        re.compile(
            r"reference key|scoring failed|metric[^\n]*(not found|failed|invalid|missing)|objective[^\n]*(not found|missing)",
            re.I,
        ),
        "A metric couldn't be evaluated. Check the metric configuration and that its reference keys exist in the dataset.",
    ),
    (
        re.compile(
            r"dataset[^\n]*(not found|empty|no items|does not exist)|no dataset|empty dataset",
            re.I,
        ),
        "The dataset couldn't be loaded. Make sure it exists and contains items.",
    ),
    (
        re.compile(r"timed out|timeout|deadline exceeded", re.I),
        "The run timed out before it could finish.",
    ),
    (
        re.compile(
            r"connection (error|refused|reset)|network|unreachable|failed to establish|econnrefused",
            re.I,
        ),
        "The run lost connection to a required service. This is usually temporary — try running it again.",
    ),
    (
        re.compile(
            r"model[^\n]*(not found|not supported|does not exist)|invalid model|unsupported model|bad ?request|context length|maximum context",
            re.I,
        ),
        "The model request was rejected. Check the model name and its parameters.",
    ),
]


def to_user_facing_message(exc: BaseException) -> str:
    """Return a high-level, user-facing message for a failed optimization run.

    Studio's own typed errors already carry curated, user-appropriate text, so we
    surface a clean version of those directly. Third-party / unexpected errors are
    classified by type + message into a friendly category, falling back to a
    generic message so we never expose a raw traceback.
    """
    # Our own typed errors — build clean text (avoid the "Original error: ..."
    # suffix some of them append, which can carry low-level detail).
    if isinstance(exc, EmptyDatasetError):
        return (
            f"The dataset '{exc.dataset_name}' is empty. "
            "Add items to it before running the optimization."
        )
    if isinstance(exc, DatasetNotFoundError):
        return (
            f"The dataset '{exc.dataset_name}' couldn't be found or isn't accessible. "
            "Create it before running the optimization."
        )
    if isinstance(exc, InvalidMetricError):
        return (
            f"The metric '{exc.metric_type}' is misconfigured. Check the metric settings "
            "and that its reference keys exist in the dataset."
        )
    if isinstance(exc, InvalidOptimizerError):
        return (
            f"The optimizer '{exc.optimizer_type}' is misconfigured. "
            "Check the optimizer settings and try again."
        )
    if isinstance(exc, InvalidConfigError):
        return "The optimization configuration is invalid. Review the run settings and try again."
    if isinstance(exc, JobMessageParseError):
        return "The optimization couldn't be started because its job details were invalid. Please try running it again."
    if isinstance(exc, OptimizationError):
        # Base / unrecognized Studio error — its own message is already curated.
        return str(exc)

    # Third-party / unexpected errors: classify by type name + message.
    haystack = f"{type(exc).__name__} {exc}"
    for pattern, message in _CATEGORIES:
        if pattern.search(haystack):
            return message

    return GENERIC_USER_MESSAGE
