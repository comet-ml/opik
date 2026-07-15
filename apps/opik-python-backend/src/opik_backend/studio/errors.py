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

# The status callback (mark_running/mark_completed/mark_error) talks to Opik via
# the generated REST client. Every exception it raises for an HTTP error response
# is opik.rest_api.core.api_error.ApiError (or a typed subclass under
# opik.rest_api.errors.*); the higher-level SDK wrappers live under
# opik.api_objects.*. A 401/403 from THAT client is an *Opik* credential/reach
# problem, not the model provider — so we must catch it before the generic auth
# regex below, which would otherwise mislabel it "model provider" (W6).
_OPIK_CLIENT_MODULE_PREFIXES = ("opik.rest_api", "opik.api_objects")

OPIK_STATUS_UPDATE_MESSAGE = (
    "We couldn't update this run's status because Opik couldn't be reached. "
    "It will be marked failed automatically."
)

# Conservative, high-signal patterns matched against "<ExceptionType> <message>".
# First match wins. Anything not clearly recognized stays generic — we would
# rather show a clean generic message than surface a low-level detail.
_CATEGORIES = [
    (
        re.compile(
            r"rate.?limit|\b429\b|too many requests|insufficient[\s_-]?quota",
            re.I,
        ),
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
            r"connection (error|refused|reset)|network (error|failure|unreachable|is unreachable)|unreachable|failed to establish|econnrefused",
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
    (
        # W12: the optimizer asks the model for structured output (reasoning /
        # candidate generation) and the response won't parse as JSON. Match the
        # common parse-failure phrasings without over-matching a plain "json" mention.
        re.compile(
            r"json ?decode|jsondecodeerror|failed to parse|could not parse|unable to parse|invalid json|malformed json|expecting (value|property|',')|unterminated string|structured[\s_-]?output|validationerror|pydantic",
            re.I,
        ),
        "The optimizer model returned an unreadable response. Try again or pick a different model.",
    ),
    (
        # W15: the isolated subprocess was killed (OOM / SIGKILL / non-zero exit)
        # before it could report a failure of its own. optimizer.py surfaces this
        # as a synthesized message; classify it here so it never leaks a raw code.
        re.compile(
            r"out of memory|\boom\b|oomkill|killed by signal|sigkill|signal 9|memory ?error|memoryerror|exceeded memory|non-?zero exit|exit ?code -?[1-9]\d*|exited with code -?[1-9]\d*|terminated unexpectedly|worker.*killed",
            re.I,
        ),
        "The run stopped unexpectedly and may have run out of memory. Try a smaller dataset or model.",
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

    # W18: the optimizer SDK raises ScoringFailedError when the objective metric
    # (LLM-as-judge) failed to score enough of the run's items. It carries the
    # failed/total counts, so build the precise "N of M" message from the object
    # instead of the SDK's own wording. Detect by class name to avoid importing
    # opik_optimizer at module load (keeps errors.py cheap to import).
    scoring_failure = _scoring_failure_message(exc)
    if scoring_failure is not None:
        return scoring_failure

    # W6: an exception from the Opik REST client (status callbacks) means we
    # couldn't reach/authenticate with Opik itself — NOT the model provider.
    # Detect it before the regex pass so its 401/403 body isn't mislabeled as a
    # model-provider auth failure by the generic auth category below.
    if _is_opik_client_error(exc):
        return OPIK_STATUS_UPDATE_MESSAGE

    # Third-party / unexpected errors: classify by type name + message.
    haystack = f"{type(exc).__name__} {exc}"
    for pattern, message in _CATEGORIES:
        if pattern.search(haystack):
            return message

    return GENERIC_USER_MESSAGE


def _is_opik_client_error(exc: BaseException) -> bool:
    """True when the exception originates from the Opik REST / API client.

    Walks the type's MRO so typed REST subclasses (UnauthorizedError, etc., under
    ``opik.rest_api.errors``) and the base ``opik.rest_api.core.api_error.ApiError``
    are all recognized. We intentionally do NOT match on generic auth strings —
    only on the defining module — so a model-provider 401 is never swallowed here.
    """
    for klass in type(exc).__mro__:
        module = getattr(klass, "__module__", "") or ""
        if any(
            module == prefix or module.startswith(prefix + ".")
            for prefix in _OPIK_CLIENT_MODULE_PREFIXES
        ):
            return True
    return False


def _scoring_failure_message(exc: BaseException) -> str | None:
    """Build the W18 message for the optimizer SDK's ScoringFailedError.

    Matched by class name (not isinstance) so this module doesn't import the
    optimizer SDK at load time. Falls back gracefully if the counts aren't present.
    """
    if type(exc).__name__ != "ScoringFailedError":
        return None
    failed = getattr(exc, "failed", None)
    total = getattr(exc, "total", None)
    if isinstance(failed, int) and isinstance(total, int) and total > 0:
        scope = f"{failed} of {total} items"
    else:
        scope = "some items"
    return (
        "The metric couldn't score this run — the judge failed or returned "
        f"invalid output on {scope}. Check the metric and its model, then run it again."
    )
