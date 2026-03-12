"""Client-side OQL trace filter evaluation."""

import logging
from datetime import datetime, timezone
from typing import Any

logger = logging.getLogger(__name__)


def _compare_values(a: Any, operator: str, b: Any) -> bool:
    """Compare two values using the given operator."""
    if operator == "=":
        return a == b
    if operator == "!=":
        return a != b
    if operator == ">":
        return a > b
    if operator == ">=":
        return a >= b
    if operator == "<":
        return a < b
    if operator == "<=":
        return a <= b
    return False


def matches_trace_filter(trace_dict: dict, filter_string: str) -> bool:
    """Evaluate an OQL filter string against a trace dict client-side.

    Used when filtering experiment traces that are fetched by ID rather than
    via a server-side paginated query (which handles filtering natively).
    Returns True if the trace passes all filter clauses, False otherwise.

    Edge case — parse failure: if ``OpikQueryLanguage`` raises ``ValueError``
    (malformed syntax or an unsupported construct), the filter cannot be
    evaluated and the function returns ``True`` (keep the trace).  A warning
    is logged so callers can detect the situation.  The rationale is that
    silently dropping traces on a best-effort client-side filter is more
    harmful than letting a few extra traces through.
    """
    from opik.api_objects.opik_query_language import OpikQueryLanguage

    try:
        oql = OpikQueryLanguage.for_traces(filter_string)
        expressions = oql.get_filter_expressions()
    except ValueError as exc:
        logger.warning(
            "Could not parse filter string %r; keeping trace: %s",
            filter_string,
            exc,
        )
        return True  # Don't silently drop traces on parse errors

    if not expressions:
        return True

    for expr in expressions:
        field = expr.get("field", "")
        operator = expr.get("operator", "")
        value = expr.get("value", "")
        field_type = expr.get("type", "string")
        key = expr.get("key", "")

        # Resolve field value: dict-key access (e.g. metadata.foo), dotted
        # composite fields (e.g. usage.total_tokens), or plain fields.
        if key:
            parent = trace_dict.get(field)
            field_value = parent.get(key) if isinstance(parent, dict) else None
        elif "." in field:
            parts = field.split(".", 1)
            parent = trace_dict.get(parts[0], {})
            field_value = parent.get(parts[1]) if isinstance(parent, dict) else None
        else:
            field_value = trace_dict.get(field)

        if operator in ("is_empty", "is_not_empty"):
            is_empty = field_value is None or field_value in ("", {}, [])
            if operator == "is_empty" and not is_empty:
                return False
            if operator == "is_not_empty" and is_empty:
                return False
            continue

        if field_value is None:
            return False

        if field_type == "date_time":
            try:
                if isinstance(field_value, datetime):
                    fv: datetime = (
                        field_value
                        if field_value.tzinfo
                        else field_value.replace(tzinfo=timezone.utc)
                    )
                else:
                    fv = datetime.fromisoformat(str(field_value).replace("Z", "+00:00"))
                fv_cmp = datetime.fromisoformat(value.replace("Z", "+00:00"))
                result = _compare_values(fv, operator, fv_cmp)
            except Exception:
                result = False
        elif field_type == "number":
            try:
                result = _compare_values(
                    float(str(field_value)), operator, float(value)
                )
            except Exception:
                result = False
        else:
            fv_str = str(field_value)
            if operator == "contains":
                result = value in fv_str
            elif operator == "not_contains":
                result = value not in fv_str
            elif operator == "starts_with":
                result = fv_str.startswith(value)
            elif operator == "ends_with":
                result = fv_str.endswith(value)
            else:
                result = _compare_values(fv_str, operator, value)

        if not result:
            return False

    return True
