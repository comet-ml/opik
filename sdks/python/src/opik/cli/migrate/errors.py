"""Errors raised by ``opik migrate`` planning and execution."""

from __future__ import annotations

from typing import Any, Dict

from opik import exceptions
from opik.rest_api.core.api_error import ApiError


class MigrationError(exceptions.OpikException):
    """Base class for migration command failures surfaced to the CLI.

    Inherits from ``OpikException`` so shared shutdown / error-tracking code
    that does ``isinstance(exc, opik.exceptions.OpikException)`` classifies
    migration failures correctly.
    """


class DatasetNotFoundError(MigrationError):
    """Raised when the source dataset name cannot be resolved."""


class AmbiguityError(MigrationError):
    """Raised when a workspace-scoped name resolves to multiple datasets."""


class ConflictError(MigrationError):
    """Raised when the destination already contains a dataset with the target name."""


class ProjectNotFoundError(MigrationError):
    """Raised when --to-project does not exist.

    Slice 1 deliberately does not auto-create the destination project: a typo
    in --to-project would otherwise silently strand a migration under a brand-
    new project the user did not mean to create.
    """


class ReplayError(MigrationError):
    """Raised when version-history replay hits an unexpected BE response.

    Used by ``cli/migrate/datasets/version_replay.py`` when the BE returns
    a response shape the replay loop can't continue from — typically a
    successful 2xx with a missing ``id`` or empty ``content`` field. These
    are catastrophic but rare and don't fit any of the user-input error
    types above; routing them through ``MigrationError`` (rather than a
    plain ``RuntimeError``) ensures ``migrate_dataset_command`` surfaces
    them via the user-facing ``MigrationError`` branch rather than the
    generic ``except Exception`` path.
    """


class UnsupportedDatasetTypeError(MigrationError):
    """Raised when the source dataset has an unrecognised ``type``.

    Slice 1 supports plain datasets and test suites (``evaluation_suite``).
    Any other type is rejected so the user can decide whether the new type
    needs explicit handling rather than being silently shoehorned into the
    plain-dataset code path.
    """


class ExperimentCascadeError(MigrationError):
    """Raised when experiment cascade hits an unrecoverable BE response.

    Used by ``cli/migrate/datasets/experiments.py`` when the BE returns a
    response shape the cascade can't continue from — typically a successful
    2xx with a missing experiment id or malformed trace/span payload. As
    with ``ReplayError``, this is catastrophic but rare and routes through
    ``MigrationError`` so the CLI surfaces it via the user-facing branch.
    """


def safe_error_envelope(exc: BaseException) -> Dict[str, Any]:
    """Build a sanitized error envelope for audit logs and console output.

    ``ApiError.__str__`` includes the response ``headers``, ``body``, and
    request URL, all of which can carry tokens, internal hostnames, or other
    response data we don't want printed to the terminal or written into a
    JSON artifact users may share. This helper returns only the fields that
    are safe to surface:

    * ``type`` — the exception class name (e.g. ``"ApiError"``)
    * ``status_code`` — the HTTP status when available (``ApiError`` only)
    * ``message`` — a short, sanitized message. For ``ApiError`` we deliberately
      do **not** include ``str(exc)``; we either pull a ``message`` field out
      of the response body when present, or fall back to the status code.
      For other exceptions we use ``str(exc)`` since those are SDK-shaped and
      don't carry remote response payloads.
    """
    envelope: Dict[str, Any] = {"type": exc.__class__.__name__}
    if isinstance(exc, ApiError):
        envelope["status_code"] = exc.status_code
        message = None
        body = getattr(exc, "body", None)
        if isinstance(body, dict):
            # ApiError bodies sometimes carry a high-level "message" or
            # "errors" field that's safe to surface; everything else (raw
            # response payloads, headers) is omitted.
            raw = body.get("message")
            if isinstance(raw, str) and raw:
                message = raw
            else:
                errors = body.get("errors")
                if isinstance(errors, list) and errors and isinstance(errors[0], str):
                    message = errors[0]
        envelope["message"] = message or f"HTTP {exc.status_code}"
    else:
        envelope["message"] = str(exc)
    return envelope


def safe_error_string(exc: BaseException) -> str:
    """Format ``safe_error_envelope`` as a single line for console output."""
    env = safe_error_envelope(exc)
    parts = [env["type"]]
    if "status_code" in env:
        parts.append(f"({env['status_code']})")
    parts.append(env["message"])
    return " ".join(parts)
