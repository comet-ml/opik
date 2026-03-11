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
