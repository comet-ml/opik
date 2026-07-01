"""Unit tests for export error-handling fixes.

Covers three HIGH-priority fixes to prevent silent data loss during export:

  HIGH-1  experiment.export_traces_by_ids: when get_trace_content raises 429,
          the internal _fetch_trace_data retry decorator must kick in so the
          trace is eventually exported, not silently dropped. Only genuine 404s
          should be skipped without retrying.

  HIGH-2  experiment.export_experiment_by_id: must re-raise on error so that
          the all.py caller can set had_errors=True (previously it swallowed
          all exceptions and returned a zero-stats tuple).

  HIGH-3  all._fetch_experiments_page_raw: 429 must trigger a retry via the
          _export_rest_retry decorator instead of silently returning ([], 0)
          and losing an entire page of experiments.
"""

from unittest.mock import MagicMock, patch

import pytest
import httpx

from opik.rest_api.core.api_error import ApiError
from opik import exceptions as opik_exceptions

_EXPERIMENT_MODULE = "opik.cli.exports.experiment"
_ALL_MODULE = "opik.cli.exports.all"


# ---------------------------------------------------------------------------
# HIGH-1: export_traces_by_ids retries 429 rather than dropping the trace
# ---------------------------------------------------------------------------


class TestExportTracesByIdsRetry:
    def _make_mock_trace(self, trace_id: str) -> MagicMock:
        t = MagicMock()
        t.model_dump.return_value = {"id": trace_id}
        return t

    def test_export_traces_by_ids__429_is_retried_not_dropped(self, tmp_path):
        """A 429 ApiError must trigger the retry decorator so the trace is
        exported, not silently dropped."""
        from opik.cli.exports.experiment import export_traces_by_ids

        call_count = 0

        def get_trace_side_effect(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                raise ApiError(status_code=429, headers={})
            return self._make_mock_trace("t1")

        client = MagicMock()
        client.get_trace_content.side_effect = get_trace_side_effect
        client.search_spans.return_value = []

        with patch(f"{_EXPERIMENT_MODULE}._fetch_trace_data.retry.sleep"):
            exported, skipped = export_traces_by_ids(
                client, ["t1"], tmp_path, "proj", None, "json", False, False
            )

        assert exported == 1, "429 must not silently drop the trace"
        assert call_count == 2, "429 must trigger a retry"

    def test_export_traces_by_ids__rate_limited_exception_is_retried(self, tmp_path):
        """OpikCloudRequestsRateLimited must also be retried."""
        from opik.cli.exports.experiment import export_traces_by_ids

        call_count = 0

        def get_trace_side_effect(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                raise opik_exceptions.OpikCloudRequestsRateLimited(
                    headers={}, retry_after=1.0
                )
            return self._make_mock_trace("t2")

        client = MagicMock()
        client.get_trace_content.side_effect = get_trace_side_effect
        client.search_spans.return_value = []

        with patch(f"{_EXPERIMENT_MODULE}._fetch_trace_data.retry.sleep"):
            exported, skipped = export_traces_by_ids(
                client, ["t2"], tmp_path, "proj", None, "json", False, False
            )

        assert exported == 1, "rate-limit exception must not silently drop the trace"
        assert call_count == 2

    def test_export_traces_by_ids__404_is_skipped_not_retried(self, tmp_path):
        """A genuine 404 (trace not found) should be skipped cleanly without retrying."""
        from opik.cli.exports.experiment import export_traces_by_ids

        call_count = 0

        def get_trace_side_effect(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            raise ApiError(status_code=404, headers={})

        client = MagicMock()
        client.get_trace_content.side_effect = get_trace_side_effect

        exported, skipped = export_traces_by_ids(
            client, ["missing-id"], tmp_path, "proj", None, "json", False, False
        )

        assert exported == 0, "404 trace must not be exported"
        assert call_count == 1, "404 must not trigger a retry"

    def test_export_traces_by_ids__success__exports_trace(self, tmp_path):
        """Happy path: trace is written to disk and counted as exported."""
        from opik.cli.exports.experiment import export_traces_by_ids

        client = MagicMock()
        client.get_trace_content.return_value = self._make_mock_trace("t3")
        client.search_spans.return_value = []

        exported, skipped = export_traces_by_ids(
            client, ["t3"], tmp_path, "proj", None, "json", False, False
        )

        assert exported == 1


# ---------------------------------------------------------------------------
# HIGH-2: export_experiment_by_id propagates errors instead of swallowing them
# ---------------------------------------------------------------------------


class TestExportExperimentByIdPropagatesErrors:
    def test_export_experiment_by_id__api_error_propagates(self, tmp_path):
        """An unexpected error must propagate so the caller can set had_errors=True."""
        from opik.cli.exports.experiment import export_experiment_by_id

        client = MagicMock()
        # Simulate a non-retriable API error (e.g. 403 Forbidden)
        client.get_experiment_by_id.side_effect = ApiError(status_code=403, headers={})

        with pytest.raises(ApiError):
            export_experiment_by_id(
                client=client,
                output_dir=tmp_path,
                project_name="proj",
                experiment_id="exp-1",
                max_traces=None,
                force=True,
                debug=False,
                format="json",
            )

    def test_export_all_experiments__experiment_failure_sets_had_errors(self, tmp_path):
        """When export_experiment_by_id raises, _export_all_experiments must set
        had_errors=True rather than silently counting it as skipped."""
        from opik.cli.exports.all import _export_all_experiments

        client = MagicMock()
        experiments_dir = tmp_path / "experiments"
        experiments_dir.mkdir()

        # Two experiments: first succeeds, second raises.
        exp1 = MagicMock()
        exp1.id = "e1"
        exp1.name = "exp-one"
        exp2 = MagicMock()
        exp2.id = "e2"
        exp2.name = "exp-two"

        def export_side_effect(
            client, output_dir, project_name, exp_id, *args, **kwargs
        ):
            if exp_id == "e2":
                raise RuntimeError("transient failure")
            m = MagicMock()
            m.get_all_trace_ids.return_value = []
            return {}, 1, m

        with (
            patch(
                f"{_ALL_MODULE}._paginate_experiments",
                return_value=[exp1, exp2],
            ),
            patch(
                f"{_ALL_MODULE}.export_experiment_by_id",
                side_effect=export_side_effect,
            ),
            patch(f"{_ALL_MODULE}.export_collected_trace_ids", return_value=(0, 0)),
        ):
            _, _, _, _, had_errors = _export_all_experiments(
                client=client,
                project_dir=tmp_path,
                project_name="proj",
                project_id=None,
                experiments_dir=experiments_dir,
                max_results=None,
                force=False,
                debug=False,
                format="json",
            )

        assert had_errors is True, "a failed experiment must set had_errors=True"


# ---------------------------------------------------------------------------
# HIGH-3: _fetch_experiments_page_raw retries 429 instead of returning [], 0
# ---------------------------------------------------------------------------


class TestFetchExperimentsPageRawRetry:
    def _make_httpx_response(self, status_code: int, headers=None, json_body=None):
        """Build a minimal httpx.Response for raise_for_status() tests."""
        headers = headers or {}
        body = json_body or {"content": [], "total": 0}
        import json as _json

        # httpx.Response.raise_for_status() requires a request to be attached.
        request = httpx.Request("GET", "http://test/v1/private/experiments")
        response = httpx.Response(
            status_code=status_code,
            headers=headers,
            content=_json.dumps(body).encode(),
            request=request,
        )
        return response

    def test_fetch_experiments_page_raw__429_raises_rate_limited_not_empty_list(
        self,
    ):
        """A 429 response must raise OpikCloudRequestsRateLimited (for the retry
        decorator), NOT silently return ([], 0)."""
        from opik.cli.exports.all import _fetch_experiments_page_raw

        client = MagicMock()
        httpx_mock = MagicMock()
        client.rest_client.experiments._raw_client._client_wrapper.httpx_client = (
            httpx_mock
        )
        httpx_mock.request.return_value = self._make_httpx_response(
            429, headers={"Retry-After": "5"}
        )

        with pytest.raises(opik_exceptions.OpikCloudRequestsRateLimited):
            _fetch_experiments_page_raw.__wrapped__(client, 1, None)

    def test_fetch_experiments_page_raw__429_is_retried_by_decorator(self):
        """When the underlying call raises 429, the decorator retries rather
        than letting a silent ([], 0) escape to the caller."""
        from opik.cli.exports.all import _fetch_experiments_page_raw

        call_count = 0

        def request_side_effect(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return self._make_httpx_response(429, headers={"Retry-After": "1"})
            return self._make_httpx_response(
                200,
                json_body={
                    "content": [{"id": "exp-1", "name": "my-exp"}],
                    "total": 1,
                },
            )

        client = MagicMock()
        httpx_mock = MagicMock()
        client.rest_client.experiments._raw_client._client_wrapper.httpx_client = (
            httpx_mock
        )
        httpx_mock.request.side_effect = request_side_effect

        with patch(f"{_ALL_MODULE}._fetch_experiments_page_raw.retry.sleep"):
            items, total = _fetch_experiments_page_raw(client, 1, None)

        assert call_count == 2, "429 must trigger a retry"
        assert len(items) == 1, "successful retry must return the page contents"
        assert total == 1
