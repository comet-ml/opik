"""Tests for rate-limiting resilience in project export.

Covers the changes introduced to handle server-side 429 throttling:

  Retry wait behaviour (via export_traces public API)
    - 429 with Retry-After header → sleep honours header value
    - 429 with Retry-After exceeding cap → sleep falls back to 30 s
    - 429 with no Retry-After → sleep is 30 s
    - non-429 transient error → sleep is in exponential backoff range [2, 60] s

  export_traces (page-fetch failures)
    - 429 on page N → page is skipped, remaining pages still fetched, had_errors=True
    - all pages succeed → had_errors=False, all traces exported
    - middle page failure → subsequent pages still exported

  export_traces (span-fetch failures)
    - bulk span fetch raises repeatedly → traces exported with empty spans, had_errors=True

  export_traces (inter-page delay)
    - time.sleep called with _PAGE_FETCH_DELAY_SECONDS between full pages
    - time.sleep not called after partial (terminal) page

  export_single_project (had_errors propagation)
    - returns 4-tuple; had_errors=False on clean run, True when errors occurred
    - first element is 0 when no traces exported or skipped, 1 otherwise
"""

import tempfile
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from opik.rest_api.core.api_error import ApiError

_MODULE = "opik.cli.exports.project"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


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


def _make_full_page(n=500, offset=0):
    """Return a page with exactly *n* mock traces (matches the internal page_size=500).

    Use *offset* to generate distinct trace IDs across pages.
    """
    return _make_page([_make_mock_trace(f"bulk-{offset + i}") for i in range(n)])


# ---------------------------------------------------------------------------
# Retry wait behaviour — tested via export_traces (public API)
# ---------------------------------------------------------------------------


class TestExportTracesRetryWaitBehaviour:
    """Verify that the export-specific retry decorator applies the correct wait
    durations when the underlying page-fetch raises errors.  Tests go through
    the public export_traces entrypoint so the actual retry decorator fires;
    time.sleep is patched to capture what tenacity passes to it.
    """

    def _run_with_first_call_raising(self, exc, tmp_path):
        """Run export_traces where the first page fetch raises *exc* then succeeds."""
        from opik.cli.exports.project import export_traces

        trace = _make_mock_trace("t1")
        call_count = 0

        def get_traces_side_effect(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                raise exc
            return _make_page([trace])

        mock_client = MagicMock()
        mock_client.rest_client.traces.get_traces_by_project.side_effect = (
            get_traces_side_effect
        )

        with patch(f"{_MODULE}._fetch_traces_page.retry.sleep") as mock_sleep:
            export_traces(
                client=mock_client,
                project_name="proj",
                project_dir=tmp_path,
                max_results=None,
                filter_string=None,
                show_progress=False,
            )

        return [c.args[0] for c in mock_sleep.call_args_list]

    def test_export_traces__page_fetch_429_with_retry_after_header__sleep_honours_header(
        self, tmp_path
    ):
        exc = ApiError(status_code=429, headers={"retry-after": "45"})
        sleep_calls = self._run_with_first_call_raising(exc, tmp_path)
        # Jitter adds 0–5 s on top of the header value.
        assert any(45.0 <= s <= 50.0 for s in sleep_calls)

    def test_export_traces__page_fetch_429_retry_after_exceeds_cap__sleep_clamped_to_max(
        self, tmp_path
    ):
        # 200 s > _EXPORT_MAX_RETRY_AFTER_SECONDS (120 s) → clamped to 120 s
        exc = ApiError(status_code=429, headers={"retry-after": "200"})
        sleep_calls = self._run_with_first_call_raising(exc, tmp_path)
        # Jitter adds 0–5 s on top of the 120 s cap.
        assert any(120.0 <= s <= 125.0 for s in sleep_calls)

    def test_export_traces__page_fetch_429_retry_after_http_date__sleep_honours_header(
        self, tmp_path
    ):
        import email.utils
        import time

        # Build an HTTP-date Retry-After 60 seconds in the future.
        future = email.utils.formatdate(timeval=time.time() + 60, usegmt=True)
        exc = ApiError(status_code=429, headers={"retry-after": future})
        sleep_calls = self._run_with_first_call_raising(exc, tmp_path)
        # Should sleep approximately 60 s (allow ±2 s for execution) plus 0–5 s jitter.
        assert any(58.0 <= s <= 67.0 for s in sleep_calls)

    def test_export_traces__page_fetch_429_no_retry_after_header__sleep_is_30s(
        self, tmp_path
    ):
        exc = ApiError(status_code=429, headers={})
        sleep_calls = self._run_with_first_call_raising(exc, tmp_path)
        # Jitter adds 0–5 s on top of the 30 s fallback.
        assert any(30.0 <= s <= 35.0 for s in sleep_calls)

    def test_export_traces__page_fetch_non_429_transient_error__sleep_is_exponential_backoff(
        self, tmp_path
    ):
        exc = ApiError(status_code=503)
        sleep_calls = self._run_with_first_call_raising(exc, tmp_path)
        # Backoff is exponential starting at 2 s, capped at 60 s.
        assert any(2.0 <= s <= 60.0 for s in sleep_calls)


# ---------------------------------------------------------------------------
# export_traces — page-fetch failures
# ---------------------------------------------------------------------------


class TestExportTracesPageFetchFailures:
    def test_export_traces__first_page_429__skips_page_and_continues(self):
        from opik.cli.exports.project import export_traces

        trace_p2 = _make_mock_trace("t-p2")
        mock_client = MagicMock()

        with tempfile.TemporaryDirectory() as tmp:
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
                        show_progress=False,
                    )

        assert had_errors is True
        assert exported == 1
        assert skipped == 0

    def test_export_traces__all_pages_succeed__happyflow(self):
        from opik.cli.exports.project import export_traces

        traces = [_make_mock_trace("t1"), _make_mock_trace("t2")]
        mock_client = MagicMock()

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
                        show_progress=False,
                    )

        assert had_errors is False
        assert exported == 2

    def test_export_traces__middle_page_fails__subsequent_pages_still_exported(self):
        """A failed page in the middle must not prevent later pages from being fetched."""
        from opik.cli.exports.project import export_traces

        # Use page_size=2 so pages with 2 traces are "full" (2 == page_size).
        # Keeps file-write count tiny (4 files) while still exercising the
        # pagination loop — the loop only breaks early on a *short* page.
        page1 = _make_full_page(2, offset=0)
        page3 = _make_full_page(2, offset=2)
        mock_client = MagicMock()

        with tempfile.TemporaryDirectory() as tmp:
            with patch(f"{_MODULE}._fetch_traces_page") as mock_fetch:
                mock_fetch.side_effect = [
                    page1,  # page 1: OK, 2 traces
                    ApiError(status_code=429),  # page 2: rate limited
                    page3,  # page 3: OK, 2 traces
                    _make_page([]),  # page 4: end
                ]
                with patch(f"{_MODULE}._fetch_spans_page", return_value=_make_page([])):
                    with patch(f"{_MODULE}.time.sleep"):
                        exported, skipped, had_errors = export_traces(
                            client=mock_client,
                            project_name="proj",
                            project_dir=Path(tmp),
                            max_results=None,
                            filter_string=None,
                            show_progress=False,
                            page_size=2,
                        )

        assert had_errors is True
        assert exported == 4  # page 1 and page 3 traces both exported

    def test_export_traces__consecutive_failures_reach_cap__export_aborted(self):
        """After MAX_CONSECUTIVE_PAGE_FAILURES pages in a row fail, the loop aborts."""
        from opik.cli.exports.project import (
            export_traces,
            MAX_CONSECUTIVE_PAGE_FAILURES,
        )

        mock_client = MagicMock()

        # Every page raises a transient error
        with tempfile.TemporaryDirectory() as tmp:
            with patch(f"{_MODULE}._fetch_traces_page") as mock_fetch:
                mock_fetch.side_effect = ApiError(status_code=503)
                with patch(f"{_MODULE}.time.sleep"):
                    exported, skipped, had_errors = export_traces(
                        client=mock_client,
                        project_name="proj",
                        project_dir=Path(tmp),
                        max_results=None,
                        filter_string=None,
                        show_progress=False,
                    )

        assert had_errors is True
        assert exported == 0
        # Should have stopped after MAX_CONSECUTIVE_PAGE_FAILURES attempts
        assert mock_fetch.call_count == MAX_CONSECUTIVE_PAGE_FAILURES

    def test_export_traces__permanent_api_error__raises_instead_of_skipping(self):
        """A 400 Bad Request must not be silently skipped — it should propagate."""
        from opik.cli.exports.project import export_traces

        mock_client = MagicMock()

        with tempfile.TemporaryDirectory() as tmp:
            with patch(f"{_MODULE}._fetch_traces_page") as mock_fetch:
                mock_fetch.side_effect = ApiError(status_code=400, body="bad request")
                with patch(f"{_MODULE}.time.sleep"):
                    with pytest.raises(ApiError) as exc_info:
                        export_traces(
                            client=mock_client,
                            project_name="proj",
                            project_dir=Path(tmp),
                            max_results=None,
                            filter_string=None,
                            show_progress=False,
                        )

        assert exc_info.value.status_code == 400
        # Should have raised immediately, not retried
        assert mock_fetch.call_count == 1


# ---------------------------------------------------------------------------
# export_traces — span-fetch failures
# ---------------------------------------------------------------------------


class TestExportTracesSpanFetchFailures:
    def test_export_traces__bulk_span_fetch_fails__traces_exported_with_empty_spans(
        self,
    ):
        """When the bulk span page fetch keeps failing, traces are still written
        (with empty span lists) and had_errors is set to True."""
        from opik.cli.exports.project import export_traces

        t1 = _make_mock_trace("t1")
        t2 = _make_mock_trace("t2")
        mock_client = MagicMock()

        with tempfile.TemporaryDirectory() as tmp:
            with patch(f"{_MODULE}._fetch_traces_page") as mock_trace_fetch:
                mock_trace_fetch.side_effect = [
                    _make_page([t1, t2]),
                    _make_page([]),
                ]
                with patch(f"{_MODULE}._fetch_spans_page") as mock_span_fetch:
                    mock_span_fetch.side_effect = ApiError(status_code=503)
                    with patch(f"{_MODULE}.time.sleep"):
                        exported, skipped, had_errors = export_traces(
                            client=mock_client,
                            project_name="proj",
                            project_dir=Path(tmp),
                            max_results=None,
                            filter_string=None,
                            show_progress=False,
                        )

        assert had_errors is True
        assert exported == 2  # both trace files written, just without spans
        assert skipped == 0


# ---------------------------------------------------------------------------
# export_traces — inter-page delay
# ---------------------------------------------------------------------------


class TestExportTracesInterPageDelay:
    def test_export_traces__multiple_full_pages__sleep_called_between_pages(self):
        """sleep is called after each full page to throttle requests."""
        from opik.cli.exports.project import export_traces, _PAGE_FETCH_DELAY_SECONDS

        # Use page_size=2 so pages with 2 traces count as "full" (2 == page_size).
        # Keeps file-write count tiny (4 files) while still exercising the sleep
        # path — the loop only skips the inter-page sleep on a *short* page.
        mock_client = MagicMock()

        with tempfile.TemporaryDirectory() as tmp:
            with patch(f"{_MODULE}._fetch_traces_page") as mock_fetch:
                mock_fetch.side_effect = [
                    _make_full_page(2, offset=0),  # page 1: full, IDs 0–1
                    _make_full_page(2, offset=2),  # page 2: full, IDs 2–3
                    _make_page([]),  # page 3: empty — end
                ]
                with patch(f"{_MODULE}._fetch_spans_page", return_value=_make_page([])):
                    with patch(f"{_MODULE}.time.sleep") as mock_sleep:
                        export_traces(
                            client=mock_client,
                            project_name="proj",
                            project_dir=Path(tmp),
                            max_results=None,
                            filter_string=None,
                            show_progress=False,
                            page_size=2,
                        )

        # sleep called once after page 1 and once after page 2.
        from unittest.mock import call as mock_call

        delay_calls = mock_sleep.call_args_list.count(
            mock_call(_PAGE_FETCH_DELAY_SECONDS)
        )
        assert delay_calls == 2

    def test_export_traces__single_partial_page__sleep_not_called(self):
        """A partial (< 100 trace) page means we're at the end — no sleep needed."""
        from opik.cli.exports.project import export_traces, _PAGE_FETCH_DELAY_SECONDS

        mock_client = MagicMock()

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
                        show_progress=False,
                    )

        from unittest.mock import call as mock_call

        delay_calls = mock_sleep.call_args_list.count(
            mock_call(_PAGE_FETCH_DELAY_SECONDS)
        )
        assert delay_calls == 0


# ---------------------------------------------------------------------------
# export_single_project — had_errors and project_exported propagation
# ---------------------------------------------------------------------------


class TestExportSingleProjectReturnValues:
    def _make_project(self, name="test-proj"):
        p = MagicMock()
        p.name = name
        p.id = "proj-id-1"
        return p

    def test_export_single_project__clean_run__had_errors_false(self):
        from opik.cli.exports.project import export_single_project

        mock_client = MagicMock()

        with tempfile.TemporaryDirectory() as tmp:
            projects_dir = Path(tmp) / "projects"
            projects_dir.mkdir()

            with (
                patch(f"{_MODULE}.export_traces") as mock_export,
                patch(f"{_MODULE}.time.sleep"),
            ):
                mock_export.return_value = (3, 0, False)
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
        proj_count, _exported, _skipped, had_errors = result
        assert had_errors is False
        assert proj_count == 1  # traces were exported

    def test_export_single_project__run_with_errors__had_errors_true(self):
        from opik.cli.exports.project import export_single_project

        mock_client = MagicMock()

        with tempfile.TemporaryDirectory() as tmp:
            projects_dir = Path(tmp) / "projects"
            projects_dir.mkdir()

            with (
                patch(f"{_MODULE}.export_traces") as mock_export,
                patch(f"{_MODULE}.time.sleep"),
            ):
                mock_export.return_value = (2, 1, True)
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

    def test_export_single_project__no_traces_found__project_count_is_zero(self):
        """When there are no traces at all, the project-exported flag must be 0."""
        from opik.cli.exports.project import export_single_project

        mock_client = MagicMock()

        with tempfile.TemporaryDirectory() as tmp:
            projects_dir = Path(tmp) / "projects"
            projects_dir.mkdir()

            with (
                patch(f"{_MODULE}.export_traces") as mock_export,
                patch(f"{_MODULE}.time.sleep"),
            ):
                mock_export.return_value = (0, 0, False)  # nothing exported or skipped
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

        proj_count, _exported, _skipped, _had_errors = result
        assert proj_count == 0

    def test_export_single_project__exception_during_export__returns_had_errors_true(
        self,
    ):
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

        assert result == (0, 0, 0, True)
