"""Generic primitives shared across entity-specific migration packages.

Slice 1 only ships dataset migration (under ``cli/migrate/datasets/``). The
two pieces here are the abstractions we have direct evidence for — they
generalise cleanly without speculating about slice 2/3 shapes:

1. ``BaseMigrationPlan`` — every per-entity plan is "a source + an ordered
   list of typed action records." Each slice's plan subclass narrows
   ``source`` and ``actions`` to its own types.
2. ``execute_plan_loop`` — the audit-bracketed for-loop ("record in_progress
   → apply → record ok / record failed + raise"). Action dispatch (``apply_fn``)
   is supplied by the caller so each entity stays in charge of its own action
   types.

Resolver and per-action class hierarchies are intentionally NOT here. Their
shapes diverge enough between datasets / experiment-cascade / version-replay
that designing them up front would either pin the wrong contract or force
later slices to contort. We extract those once a second concrete
implementation makes the right shape obvious.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List

from .audit import AuditLog
from .errors import safe_error_envelope


@dataclass(kw_only=True)
class BaseMigrationPlan:
    """Shape every per-entity migration plan shares.

    Subclasses keep this skeleton and narrow the field types — e.g. the
    dataset slice's plan declares ``source: ResolvedDataset`` and an
    ``actions`` list whose members are dataset-specific action dataclasses.

    ``kw_only=True`` so subclasses can freely add required (non-default)
    fields without Python's "non-default after default" inheritance error.
    """

    source: Any
    actions: List[Any] = field(default_factory=list)


def execute_plan_loop(
    actions: List[Any],
    audit: AuditLog,
    *,
    apply_fn: Callable[[Any], None],
    details_fn: Callable[[Any], Dict[str, Any]],
) -> None:
    """Apply ``actions`` in order, audit-bracketing each one.

    Caller supplies:

    * ``apply_fn(action)`` — performs the side effect for one action. May raise.
    * ``details_fn(action)`` — returns a JSON-serialisable dict that goes into
      the audit log entry. The required key ``type`` distinguishes action
      kinds in the audit JSON.

    On exception we record ``failed`` (with a sanitised error envelope so
    response bodies / headers / tokens never reach the audit log) and
    re-raise so the caller's outer error handler can finalise.
    """
    for action in actions:
        details = details_fn(action)
        audit.record(type=details["type"], status="in_progress", details=details)
        try:
            apply_fn(action)
        except Exception as exc:
            audit.record(
                type=details["type"],
                status="failed",
                details=details,
                error=safe_error_envelope(exc),
            )
            raise
        audit.record(type=details["type"], status="ok", details=details)


def record_planned_loop(
    actions: List[Any],
    audit: AuditLog,
    *,
    details_fn: Callable[[Any], Dict[str, Any]],
) -> None:
    """Record every action as ``planned`` (used for ``--dry-run``)."""
    for action in actions:
        details = details_fn(action)
        audit.record(type=details["type"], status="planned", details=details)
