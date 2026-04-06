"""Tests for rate-limiting resilience in project export.

Covers the changes introduced to handle server-side 429 throttling:

  _export_wait_duration
    - 429 with Retry-After header → honour header value
    - 429 with Retry-After exceeding cap → fall back to 30 s default
    - 429 with no Retry-After header → return 30 s
    - non-429 ApiError → exponential backoff in [10, 60] s range
    - non-ApiError exception → exponential backoff in [10, 60] s range

  export_traces (page-fetch failures)
    - 429 on page N → page is skipped, remaining pages still fetched, had_errors=True
    - all pages succeed → had_errors=False, all traces exported

  export_traces (span-fetch failures)
    - span fetch raises after retries → that trace skipped, others exported, had_errors=True

  export_traces (inter-page delay)
    - time.sleep called with _PAGE_FETCH_DELAY_SECONDS between pages

  export_single_project (had_errors propagation)
    - returns 4-tuple; had_errors=False on clean run, True when errors occurred

  MAX_WORKERS
    - constant is <= 3 to avoid triggering rate limits
"""

import tempfile
from pathlib import Path
from unittest.mock import MagicMock, patch


from opik.rest_api.core.api_error import ApiError

_MODULE = "opik.cli.exports.project"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_retry_state(exc):
    """Return a minimal mock RetryCallState whose outcome holds *exc*."""
    state = MagicMock()
    state.outcome.exception.return_value = exc
    # attempt_number is used by tenacity's wait_exponential internals
    state.attempt_number = 1
    return state


def _make_mock_trace(trace_id: str) -> MagicMock:
    t = MagicMock()
    t.id = trace_id
    t.name = f"trace-{trace_id}"
    t.model_dump.return_value = {
        "id": trace_id,
        "name": f"trace-{trace_id}",
        "start_time": None,
        "end_time": None,
        "input": {},
        "output": {},
        "metadata": {},
        "tags": [],
        "feedback_scores": [],
        "error_info": None,
        "thread_id": None,
        "created_at": None,
        "created_by": None,
        "last_updated_at": None,
        "last_updated_by": None,
        "visibility_mode": None,
        "ttft": None,
        "project_name": "test-project",
    }
    return t


def _make_page(traces, total=None):
    page = MagicMock()
    page.content = traces
    page.total = total if total is not None else len(traces)
    return page


def _make_full_page(n=100, offset=0):
    """Return a page with exactly *n* mock traces (matches the internal page_size=100).

    Use *offset* to generate distinct trace IDs across pages.
    """
    return _make_page([_make_mock_trace(f"bulk-{offset + i}") for i in range(n)])


def _make_mock_client(pages):
    """Return a mock Opik client whose trace pages come from *pages* (list of lists)."""
    client = MagicMock()
    page_objects = [_make_page(t) for t in pages]
    # After the last real page return an empty page so the loop terminates
    page_objects.append(_make_page([]))
    client.rest_client.traces.get_traces_by_project.side_effect = page_objects
    client.search_spans.return_value = []
    return client


# ---------------------------------------------------------------------------
# _export_wait_duration
# ---------------------------------------------------------------------------


class TestExportWaitDuration:
    def _wait(self, exc):
        from opik.cli.exports.project import _export_wait_duration

        return _export_wait_duration(_make_retry_state(exc))

    def test_429_with_retry_after_header_honours_value(self):
        exc = ApiError(status_code=429, headers={"retry-after": "45"})
        assert self._wait(exc) == 45.0

    def test_429_with_retry_after_exceeding_cap_falls_back_to_30s(self):
        # 200 s > _EXPORT_MAX_RETRY_AFTER_SECONDS (120 s) → fall back to 30 s default
        exc = ApiError(status_code=429, headers={"retry-after": "200"})
        assert self._wait(exc) == 30.0

    def test_429_with_no_retry_after_header_returns_30s(self):
        exc = ApiError(status_code=429, headers={})
        assert self._wait(exc) == 30.0

    def test_429_with_none_headers_returns_30s(self):
        exc = ApiError(status_code=429, headers=None)
        assert self._wait(exc) == 30.0

    def test_non_429_api_error_returns_exponential_backoff(self):
        exc = ApiError(status_code=503)
        wait = self._wait(exc)
        assert 10.0 <= wait <= 60.0

    def test_non_api_error_returns_exponential_backoff(self):
        exc = ConnectionError("network gone")
        wait = self._wait(exc)
        assert 10.0 <= wait <= 60.0


# ---------------------------------------------------------------------------
# MAX_WORKERS
# ---------------------------------------------------------------------------


class TestMaxWorkers:
    def test_max_workers_is_at_most_3(self):
        from opik.cli.exports.project import MAX_WORKERS

        assert MAX_WORKERS <= 3, (
            f"MAX_WORKERS={MAX_WORKERS} is too high; keep it ≤ 3 to avoid "
            "triggering server-side rate limits during bulk exports"
        )


# ---------------------------------------------------------------------------
# export_traces — page-fetch failures
# ---------------------------------------------------------------------------


class TestExportTracesPageFetchFailures:
    """A 429 on one page must be skipped; remaining pages must still be fetched."""

    def test_429_on_first_page_skips_it_and_continues(self):
        from opik.cli.exports.project import export_traces

        trace_p2 = _make_mock_trace("t-p2")
        mock_client = MagicMock()
        mock_client.search_spans.return_value = []

        # Page 1 raises 429; page 2 returns one trace; page 3 is empty (end)
        mock_client.rest_client.traces.get_traces_by_project.side_effect = [
            ApiError(status_code=429, body="rate limited"),
            _make_page([trace_p2]),
            _make_page([]),
        ]

        with tempfile.TemporaryDirectory() as tmp:
            with patch(
                f"{_MODULE}._fetch_traces_page",
                wraps=lambda *a, **kw: (
                    mock_client.rest_client.traces.get_traces_by_project(*a, **kw)
                ),
            ):
                # Patch _fetch_traces_page directly to bypass the retry decorator
                with patch(f"{_MODULE}._fetch_traces_page") as mock_fetch:
                    mock_fetch.side_effect = [
                        ApiError(status_code=429, body="rate limited"),
                        _make_page([trace_p2]),
                        _make_page([]),
                    ]
                    with patch(f"{_MODULE}.time.sleep"):
                        exported, skipped, had_errors = export_traces(
                            client=mock_client,
                            project_name="proj",
                            project_dir=Path(tmp),
                            max_results=None,
                            filter_string=None,
                        )

        assert had_errors is True
        assert exported == 1  # page 2 trace was exported
        assert skipped == 0

    def test_all_pages_succeed_had_errors_false(self):
        from opik.cli.exports.project import export_traces

        traces = [_make_mock_trace("t1"), _make_mock_trace("t2")]
        mock_client = MagicMock()
        mock_client.search_spans.return_value = []

        with tempfile.TemporaryDirectory() as tmp:
            with patch(f"{_MODULE}._fetch_traces_page") as mock_fetch:
                mock_fetch.side_effect = [
                    _make_page(traces),
                    _make_page([]),
                ]
                with patch(f"{_MODULE}.time.sleep"):
                    exported, skipped, had_errors = export_traces(
                        client=mock_client,
                        project_name="proj",
                        project_dir=Path(tmp),
                        max_results=None,
                        filter_string=None,
                    )

        assert had_errors is False
        assert exported == 2

    def test_middle_page_failure_does_not_stop_subsequent_pages(self):
        """A failed page in the middle must not prevent later pages from being fetched."""
        from opik.cli.exports.project import export_traces

        # Pages must be full (100 traces) so the loop doesn't break on the
        # short-page termination check before reaching the failed page.
        # Use distinct offsets so page3 trace IDs don't collide with page1.
        page1 = _make_full_page(100, offset=0)
        page3 = _make_full_page(100, offset=100)
        mock_client = MagicMock()
        mock_client.search_spans.return_value = []

        with tempfile.TemporaryDirectory() as tmp:
            with patch(f"{_MODULE}._fetch_traces_page") as mock_fetch:
                mock_fetch.side_effect = [
                    page1,  # page 1: OK, 100 traces
                    ApiError(status_code=429),  # page 2: rate limited
                    page3,  # page 3: OK, 100 traces
                    _make_page([]),  # page 4: end
                ]
                with patch(f"{_MODULE}.time.sleep"):
                    exported, skipped, had_errors = export_traces(
                        client=mock_client,
                        project_name="proj",
                        project_dir=Path(tmp),
                        max_results=None,
                        filter_string=None,
                    )

        assert had_errors is True
        assert exported == 200  # page 1 and page 3 traces both exported


# ---------------------------------------------------------------------------
# export_traces — span-fetch failures
# ---------------------------------------------------------------------------


class TestExportTracesSpanFetchFailures:
    def test_span_fetch_failure_skips_trace_but_exports_others(self):
        from opik.cli.exports.project import export_traces

        t_ok = _make_mock_trace("t-ok")
        t_fail = _make_mock_trace("t-fail")
        mock_client = MagicMock()

        def span_side_effect(project_name, trace_id, **kwargs):
            if trace_id == "t-fail":
                raise ApiError(status_code=429, body="rate limited")
            return []

        mock_client.search_spans.side_effect = span_side_effect

        with tempfile.TemporaryDirectory() as tmp:
            with patch(f"{_MODULE}._fetch_traces_page") as mock_fetch:
                mock_fetch.side_effect = [
                    _make_page([t_ok, t_fail]),
                    _make_page([]),
                ]
                with patch(f"{_MODULE}._fetch_spans") as mock_spans:

                    def fetch_spans_side_effect(client, trace, project_name):
                        if trace.id == "t-fail":
                            raise ApiError(status_code=429)
                        return trace, []

                    mock_spans.side_effect = fetch_spans_side_effect
                    with patch(f"{_MODULE}.time.sleep"):
                        exported, skipped, had_errors = export_traces(
                            client=mock_client,
                            project_name="proj",
                            project_dir=Path(tmp),
                            max_results=None,
                            filter_string=None,
                        )

        assert had_errors is True
        assert exported == 1  # only t-ok written
        assert skipped == 0


# ---------------------------------------------------------------------------
# export_traces — inter-page delay
# ---------------------------------------------------------------------------


class TestExportTracesInterPageDelay:
    def test_sleep_called_between_full_pages(self):
        """sleep is called after each full page (page_size=100) to throttle requests."""
        from opik.cli.exports.project import export_traces, _PAGE_FETCH_DELAY_SECONDS

        # Must use full pages (100 traces) — the loop breaks on short pages before
        # reaching the sleep call, so partial pages do not trigger a sleep.
        mock_client = MagicMock()
        mock_client.search_spans.return_value = []

        with tempfile.TemporaryDirectory() as tmp:
            with patch(f"{_MODULE}._fetch_traces_page") as mock_fetch:
                mock_fetch.side_effect = [
                    _make_full_page(100, offset=0),  # page 1: full, IDs 0–99
                    _make_full_page(100, offset=100),  # page 2: full, IDs 100–199
                    _make_page([]),  # page 3: empty — end
                ]
                with patch(f"{_MODULE}.time.sleep") as mock_sleep:
                    export_traces(
                        client=mock_client,
                        project_name="proj",
                        project_dir=Path(tmp),
                        max_results=None,
                        filter_string=None,
                    )

        # sleep called once after page 1 and once after page 2
        assert mock_sleep.call_count == 2
        mock_sleep.assert_called_with(_PAGE_FETCH_DELAY_SECONDS)

    def test_sleep_not_called_for_partial_last_page(self):
        """A partial (< 100 trace) page means we're at the end — no sleep needed."""
        from opik.cli.exports.project import export_traces

        mock_client = MagicMock()
        mock_client.search_spans.return_value = []

        with tempfile.TemporaryDirectory() as tmp:
            with patch(f"{_MODULE}._fetch_traces_page") as mock_fetch:
                # Single partial page — loop breaks immediately on short-page check
                mock_fetch.side_effect = [
                    _make_page([_make_mock_trace("t1")]),
                ]
                with patch(f"{_MODULE}.time.sleep") as mock_sleep:
                    export_traces(
                        client=mock_client,
                        project_name="proj",
                        project_dir=Path(tmp),
                        max_results=None,
                        filter_string=None,
                    )

        assert mock_sleep.call_count == 0


# ---------------------------------------------------------------------------
# export_single_project — had_errors propagation
# ---------------------------------------------------------------------------


class TestExportSingleProjectHadErrors:
    def _make_project(self, name="test-proj"):
        p = MagicMock()
        p.name = name
        p.id = "proj-id-1"
        return p

    def test_clean_run_returns_had_errors_false(self):
        from opik.cli.exports.project import export_single_project

        mock_client = MagicMock()

        with tempfile.TemporaryDirectory() as tmp:
            projects_dir = Path(tmp) / "projects"
            projects_dir.mkdir()

            with (
                patch(f"{_MODULE}.export_traces") as mock_export,
                patch(f"{_MODULE}.time.sleep"),
            ):
                mock_export.return_value = (
                    3,
                    0,
                    False,
                )  # exported=3, skipped=0, had_errors=False
                result = export_single_project(
                    client=mock_client,
                    project=self._make_project(),
                    output_dir=projects_dir,
                    filter_string=None,
                    max_results=None,
                    force=False,
                    debug=False,
                    format="json",
                )

        assert len(result) == 4
        _proj_count, _exported, _skipped, had_errors = result
        assert had_errors is False

    def test_run_with_errors_returns_had_errors_true(self):
        from opik.cli.exports.project import export_single_project

        mock_client = MagicMock()

        with tempfile.TemporaryDirectory() as tmp:
            projects_dir = Path(tmp) / "projects"
            projects_dir.mkdir()

            with (
                patch(f"{_MODULE}.export_traces") as mock_export,
                patch(f"{_MODULE}.time.sleep"),
            ):
                mock_export.return_value = (
                    2,
                    1,
                    True,
                )  # exported=2, skipped=1, had_errors=True
                result = export_single_project(
                    client=mock_client,
                    project=self._make_project(),
                    output_dir=projects_dir,
                    filter_string=None,
                    max_results=None,
                    force=False,
                    debug=False,
                    format="json",
                )

        assert len(result) == 4
        _proj_count, _exported, _skipped, had_errors = result
        assert had_errors is True

    def test_exception_during_export_returns_had_errors_true(self):
        from opik.cli.exports.project import export_single_project

        mock_client = MagicMock()

        with tempfile.TemporaryDirectory() as tmp:
            projects_dir = Path(tmp) / "projects"
            projects_dir.mkdir()

            with patch(f"{_MODULE}.export_traces") as mock_export:
                mock_export.side_effect = RuntimeError("unexpected failure")
                result = export_single_project(
                    client=mock_client,
                    project=self._make_project(),
                    output_dir=projects_dir,
                    filter_string=None,
                    max_results=None,
                    force=False,
                    debug=False,
                    format="json",
                )

        assert len(result) == 4
        assert result == (0, 0, 0, True)
