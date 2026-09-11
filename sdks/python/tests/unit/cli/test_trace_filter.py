"""Unit tests for matches_trace_filter in exports/trace_filter.py."""

import sys
from types import ModuleType
from unittest.mock import MagicMock, patch


from opik.cli.exports.trace_filter import matches_trace_filter


def _make_oql_module(mock_oql: MagicMock) -> ModuleType:
    """Return a fake opik.api_objects.opik_query_language module whose
    OpikQueryLanguage.for_traces() returns *mock_oql*."""
    mod = ModuleType("opik.api_objects.opik_query_language")
    cls = MagicMock()
    cls.for_traces.return_value = mock_oql
    mod.OpikQueryLanguage = cls  # type: ignore[attr-defined]
    return mod


class TestMatchesTraceFilter:
    def test_matches_trace_filter__date_time_gte__matches_trace_after_cutoff(self):
        """A trace whose created_at is after the cutoff must pass the filter."""
        trace = {
            "created_at": "2024-06-01T12:00:00+00:00",
        }

        mock_oql = MagicMock()
        mock_oql.get_filter_expressions.return_value = [
            {
                "field": "created_at",
                "operator": ">=",
                "value": "2024-01-01T00:00:00+00:00",
                "type": "date_time",
                "key": "",
            }
        ]

        fake_mod = _make_oql_module(mock_oql)
        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            result = matches_trace_filter(trace, 'created_at >= "2024-01-01T00:00:00Z"')

        assert result is True

    def test_matches_trace_filter__date_time_gte__excludes_trace_before_cutoff(self):
        """A trace whose created_at is before the cutoff must be excluded."""
        trace = {
            "created_at": "2023-06-01T12:00:00+00:00",
        }

        mock_oql = MagicMock()
        mock_oql.get_filter_expressions.return_value = [
            {
                "field": "created_at",
                "operator": ">=",
                "value": "2024-01-01T00:00:00+00:00",
                "type": "date_time",
                "key": "",
            }
        ]

        fake_mod = _make_oql_module(mock_oql)
        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            result = matches_trace_filter(trace, 'created_at >= "2024-01-01T00:00:00Z"')

        assert result is False

    def test_matches_trace_filter__invalid_filter__returns_true_with_warning(self):
        """An unparseable filter string must return True and log a warning."""
        import opik.cli.exports.trace_filter as trace_filter_module

        trace = {"name": "my-trace"}

        fake_mod = ModuleType("opik.api_objects.opik_query_language")
        cls = MagicMock()
        cls.for_traces.side_effect = ValueError("bad filter syntax")
        fake_mod.OpikQueryLanguage = cls  # type: ignore[attr-defined]

        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            with patch.object(trace_filter_module.logger, "warning") as mock_warn:
                result = matches_trace_filter(trace, "THIS IS NOT VALID OQL")

        assert result is True
        mock_warn.assert_called_once()
        # The warning message should contain both the filter string and the exception
        warning_args = mock_warn.call_args
        assert "bad filter syntax" in str(warning_args) or any(
            "bad filter syntax" in str(a) for a in warning_args.args
        )

    def test_matches_trace_filter__string_contains__happyflow(self):
        """A string 'contains' filter must match when the substring is present."""
        trace = {"name": "evaluation-run-42"}

        mock_oql = MagicMock()
        mock_oql.get_filter_expressions.return_value = [
            {
                "field": "name",
                "operator": "contains",
                "value": "evaluation",
                "type": "string",
                "key": "",
            }
        ]

        fake_mod = _make_oql_module(mock_oql)
        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            result = matches_trace_filter(trace, 'name contains "evaluation"')

        assert result is True

    def test_matches_trace_filter__no_expressions__returns_true(self):
        """When the filter parses to zero expressions every trace must pass."""
        trace = {"name": "any-trace"}

        mock_oql = MagicMock()
        mock_oql.get_filter_expressions.return_value = []

        fake_mod = _make_oql_module(mock_oql)
        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            result = matches_trace_filter(trace, "")

        assert result is True

    # ------------------------------------------------------------------
    # number type
    # ------------------------------------------------------------------

    def test_matches_trace_filter__number_gte__matches(self):
        """A number >= filter must match when the field value satisfies it."""
        trace = {"usage": {"total_tokens": 500}}

        mock_oql = MagicMock()
        mock_oql.get_filter_expressions.return_value = [
            {
                "field": "usage.total_tokens",
                "operator": ">=",
                "value": "100",
                "type": "number",
                "key": "",
            }
        ]

        fake_mod = _make_oql_module(mock_oql)
        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            result = matches_trace_filter(trace, 'usage.total_tokens >= "100"')

        assert result is True

    def test_matches_trace_filter__number_lt__excludes(self):
        """A number < filter must exclude a trace that doesn't satisfy it."""
        trace = {"usage": {"total_tokens": 50}}

        mock_oql = MagicMock()
        mock_oql.get_filter_expressions.return_value = [
            {
                "field": "usage.total_tokens",
                "operator": "<",
                "value": "10",
                "type": "number",
                "key": "",
            }
        ]

        fake_mod = _make_oql_module(mock_oql)
        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            result = matches_trace_filter(trace, 'usage.total_tokens < "10"')

        assert result is False

    # ------------------------------------------------------------------
    # is_empty / is_not_empty
    # ------------------------------------------------------------------

    def test_matches_trace_filter__is_empty__none_field__matches(self):
        """is_empty must match when the field is absent / None."""
        trace = {}  # 'error' key is absent

        mock_oql = MagicMock()
        mock_oql.get_filter_expressions.return_value = [
            {
                "field": "error",
                "operator": "is_empty",
                "value": "",
                "type": "string",
                "key": "",
            }
        ]

        fake_mod = _make_oql_module(mock_oql)
        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            result = matches_trace_filter(trace, "error is_empty")

        assert result is True

    def test_matches_trace_filter__is_empty__non_empty_field__excludes(self):
        """is_empty must exclude when the field has a value."""
        trace = {"error": "something went wrong"}

        mock_oql = MagicMock()
        mock_oql.get_filter_expressions.return_value = [
            {
                "field": "error",
                "operator": "is_empty",
                "value": "",
                "type": "string",
                "key": "",
            }
        ]

        fake_mod = _make_oql_module(mock_oql)
        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            result = matches_trace_filter(trace, "error is_empty")

        assert result is False

    def test_matches_trace_filter__is_not_empty__non_empty_field__matches(self):
        """is_not_empty must match when the field is present and non-empty."""
        trace = {"name": "my-trace"}

        mock_oql = MagicMock()
        mock_oql.get_filter_expressions.return_value = [
            {
                "field": "name",
                "operator": "is_not_empty",
                "value": "",
                "type": "string",
                "key": "",
            }
        ]

        fake_mod = _make_oql_module(mock_oql)
        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            result = matches_trace_filter(trace, "name is_not_empty")

        assert result is True

    def test_matches_trace_filter__is_not_empty__none_field__excludes(self):
        """is_not_empty must exclude when the field is absent."""
        trace = {}

        mock_oql = MagicMock()
        mock_oql.get_filter_expressions.return_value = [
            {
                "field": "name",
                "operator": "is_not_empty",
                "value": "",
                "type": "string",
                "key": "",
            }
        ]

        fake_mod = _make_oql_module(mock_oql)
        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            result = matches_trace_filter(trace, "name is_not_empty")

        assert result is False

    # ------------------------------------------------------------------
    # Additional string operators
    # ------------------------------------------------------------------

    def test_matches_trace_filter__string_not_contains__excludes(self):
        """not_contains must exclude when the substring is present."""
        trace = {"name": "evaluation-run-42"}

        mock_oql = MagicMock()
        mock_oql.get_filter_expressions.return_value = [
            {
                "field": "name",
                "operator": "not_contains",
                "value": "evaluation",
                "type": "string",
                "key": "",
            }
        ]

        fake_mod = _make_oql_module(mock_oql)
        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            result = matches_trace_filter(trace, 'name not_contains "evaluation"')

        assert result is False

    def test_matches_trace_filter__string_starts_with__matches(self):
        """starts_with must match when the field begins with the prefix."""
        trace = {"name": "evaluation-run-42"}

        mock_oql = MagicMock()
        mock_oql.get_filter_expressions.return_value = [
            {
                "field": "name",
                "operator": "starts_with",
                "value": "evaluation",
                "type": "string",
                "key": "",
            }
        ]

        fake_mod = _make_oql_module(mock_oql)
        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            result = matches_trace_filter(trace, 'name starts_with "evaluation"')

        assert result is True

    def test_matches_trace_filter__string_ends_with__matches(self):
        """ends_with must match when the field ends with the suffix."""
        trace = {"name": "evaluation-run-42"}

        mock_oql = MagicMock()
        mock_oql.get_filter_expressions.return_value = [
            {
                "field": "name",
                "operator": "ends_with",
                "value": "42",
                "type": "string",
                "key": "",
            }
        ]

        fake_mod = _make_oql_module(mock_oql)
        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            result = matches_trace_filter(trace, 'name ends_with "42"')

        assert result is True

    # ------------------------------------------------------------------
    # Nested / dotted field access
    # ------------------------------------------------------------------

    def test_matches_trace_filter__dotted_field__matches(self):
        """Dotted fields like 'usage.total_tokens' should be resolved correctly."""
        trace = {"usage": {"total_tokens": 200}}

        mock_oql = MagicMock()
        mock_oql.get_filter_expressions.return_value = [
            {
                "field": "usage.total_tokens",
                "operator": "=",
                "value": "200",
                "type": "number",
                "key": "",
            }
        ]

        fake_mod = _make_oql_module(mock_oql)
        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            result = matches_trace_filter(trace, 'usage.total_tokens = "200"')

        assert result is True

    def test_matches_trace_filter__key_field_access__matches(self):
        """Expressions with a non-empty 'key' resolve via trace_dict[field][key]."""
        trace = {"metadata": {"env": "production"}}

        mock_oql = MagicMock()
        mock_oql.get_filter_expressions.return_value = [
            {
                "field": "metadata",
                "operator": "=",
                "value": "production",
                "type": "string",
                "key": "env",
            }
        ]

        fake_mod = _make_oql_module(mock_oql)
        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            result = matches_trace_filter(trace, 'metadata.env = "production"')

        assert result is True

    # ------------------------------------------------------------------
    # None field value → False
    # ------------------------------------------------------------------

    def test_matches_trace_filter__missing_field__returns_false(self):
        """When a non-empty-check expression references a missing field the trace must be excluded."""
        trace = {}  # 'name' is absent

        mock_oql = MagicMock()
        mock_oql.get_filter_expressions.return_value = [
            {
                "field": "name",
                "operator": "=",
                "value": "something",
                "type": "string",
                "key": "",
            }
        ]

        fake_mod = _make_oql_module(mock_oql)
        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            result = matches_trace_filter(trace, 'name = "something"')

        assert result is False

    # ------------------------------------------------------------------
    # Multiple expressions (AND semantics)
    # ------------------------------------------------------------------

    def test_matches_trace_filter__multiple_expressions__all_must_pass(self):
        """When two expressions are given both must be satisfied (AND logic)."""
        trace = {
            "name": "evaluation-run-42",
            "created_at": "2024-06-01T12:00:00+00:00",
        }

        mock_oql = MagicMock()
        mock_oql.get_filter_expressions.return_value = [
            {
                "field": "name",
                "operator": "contains",
                "value": "evaluation",
                "type": "string",
                "key": "",
            },
            {
                "field": "created_at",
                "operator": ">=",
                "value": "2024-01-01T00:00:00+00:00",
                "type": "date_time",
                "key": "",
            },
        ]

        fake_mod = _make_oql_module(mock_oql)
        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            result = matches_trace_filter(trace, 'name contains "evaluation"')

        assert result is True

    def test_matches_trace_filter__multiple_expressions__first_fails__returns_false(
        self,
    ):
        """When the first of two expressions fails the trace must be excluded."""
        trace = {
            "name": "other-run",
            "created_at": "2024-06-01T12:00:00+00:00",
        }

        mock_oql = MagicMock()
        mock_oql.get_filter_expressions.return_value = [
            {
                "field": "name",
                "operator": "contains",
                "value": "evaluation",
                "type": "string",
                "key": "",
            },
            {
                "field": "created_at",
                "operator": ">=",
                "value": "2024-01-01T00:00:00+00:00",
                "type": "date_time",
                "key": "",
            },
        ]

        fake_mod = _make_oql_module(mock_oql)
        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            result = matches_trace_filter(trace, 'name contains "evaluation"')

        assert result is False

    # ------------------------------------------------------------------
    # date_time: naive datetime field value (no tzinfo)
    # ------------------------------------------------------------------

    def test_matches_trace_filter__date_time_naive__treated_as_utc(self):
        """A naive datetime field value must be compared as UTC."""
        from datetime import datetime

        trace = {"created_at": datetime(2024, 6, 1, 12, 0, 0)}  # no tzinfo

        mock_oql = MagicMock()
        mock_oql.get_filter_expressions.return_value = [
            {
                "field": "created_at",
                "operator": ">=",
                "value": "2024-01-01T00:00:00+00:00",
                "type": "date_time",
                "key": "",
            }
        ]

        fake_mod = _make_oql_module(mock_oql)
        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            result = matches_trace_filter(trace, 'created_at >= "2024-01-01T00:00:00Z"')

        assert result is True

    # ------------------------------------------------------------------
    # date_time: unparseable field value → result=False (not exception)
    # ------------------------------------------------------------------

    def test_matches_trace_filter__date_time_unparseable_value__returns_false(self):
        """An unparseable date_time field value must exclude the trace, not raise."""
        trace = {"created_at": "not-a-date"}

        mock_oql = MagicMock()
        mock_oql.get_filter_expressions.return_value = [
            {
                "field": "created_at",
                "operator": ">=",
                "value": "2024-01-01T00:00:00+00:00",
                "type": "date_time",
                "key": "",
            }
        ]

        fake_mod = _make_oql_module(mock_oql)
        with patch.dict(
            sys.modules, {"opik.api_objects.opik_query_language": fake_mod}
        ):
            result = matches_trace_filter(trace, 'created_at >= "2024-01-01T00:00:00Z"')

        assert result is False
