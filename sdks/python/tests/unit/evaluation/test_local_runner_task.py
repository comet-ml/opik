import itertools
from unittest import mock

import httpx
import pytest

from opik.evaluation.local_runner_task import LocalRunnerTask


def _monotonic_side_effect(*values):
    """Return a side_effect iterator that yields *values* then repeats the last one forever.

    Patching ``time.monotonic`` via a module reference replaces the function on the
    shared ``time`` module, so background threads (e.g. QueueConsumerThread) may also
    call it.  A plain list side_effect exhausts and raises ``StopIteration``; this
    helper prevents that.
    """
    return itertools.chain(values, itertools.repeat(values[-1]))


@pytest.fixture
def mock_rest_client():
    rest_client = mock.MagicMock()
    return rest_client


@pytest.fixture
def mock_opik_client(mock_rest_client):
    client = mock.MagicMock()
    client.rest_client = mock_rest_client
    return client


@pytest.fixture
def patch_get_client(mock_opik_client):
    with mock.patch(
        "opik.evaluation.local_runner_task.opik_client.get_client_cached",
        return_value=mock_opik_client,
    ) as patched:
        yield patched


@pytest.fixture
def patch_resolve_project_id():
    with mock.patch(
        "opik.evaluation.local_runner_task.rest_helpers.resolve_project_id_by_name",
        return_value="project-id-123",
    ) as patched:
        yield patched


@pytest.fixture
def patch_sleep():
    with mock.patch(
        "opik.evaluation.local_runner_task.time.sleep",
    ) as patched:
        yield patched


@pytest.fixture
def patch_distributed_headers():
    with mock.patch(
        "opik.evaluation.local_runner_task.opik_context.get_distributed_trace_headers",
        return_value={
            "opik_trace_id": "trace-abc",
            "opik_parent_span_id": "span-xyz",
        },
    ) as patched:
        yield patched


def _make_job(status, result=None, error=None):
    job = mock.MagicMock()
    job.status = status
    job.result = result
    job.error = error
    return job


def _make_create_job_response(location=""):
    resp = mock.MagicMock()
    resp.headers = {"location": location}
    return resp


class TestLocalRunnerTask:
    def test_call__happyflow(
        self,
        mock_rest_client,
        patch_get_client,
        patch_resolve_project_id,
        patch_sleep,
    ):
        mock_rest_client.runners.with_raw_response.create_job.return_value = (
            _make_create_job_response("/v1/private/local-runners/jobs/job-42")
        )
        mock_rest_client.runners.get_job.return_value = _make_job(
            "completed", result={"result": "answer-text"}
        )

        task = LocalRunnerTask(
            project_name="My Project",
            agent_name="my_agent",
        )
        output = task({"question": "What is AI?"})

        assert output == {
            "input": {"question": "What is AI?"},
            "output": "answer-text",
        }
        mock_rest_client.runners.with_raw_response.create_job.assert_called_once_with(
            agent_name="my_agent",
            project_id="project-id-123",
            inputs={"question": "What is AI?"},
        )

    def test_call__job_failed__raises_runtime_error(
        self,
        mock_rest_client,
        patch_get_client,
        patch_resolve_project_id,
        patch_sleep,
    ):
        mock_rest_client.runners.with_raw_response.create_job.return_value = (
            _make_create_job_response("/jobs/job-99")
        )
        mock_rest_client.runners.get_job.return_value = _make_job(
            "failed", error="Agent crashed"
        )

        task = LocalRunnerTask(
            project_name="My Project",
            agent_name="my_agent",
        )
        with pytest.raises(RuntimeError, match="Agent crashed"):
            task({"input": "test"})

    def test_call__timeout__raises_timeout_error(
        self,
        mock_rest_client,
        patch_get_client,
        patch_resolve_project_id,
        patch_sleep,
    ):
        mock_rest_client.runners.with_raw_response.create_job.return_value = (
            _make_create_job_response("/jobs/job-slow")
        )
        mock_rest_client.runners.get_job.return_value = _make_job("pending")

        task = LocalRunnerTask(
            project_name="My Project",
            agent_name="my_agent",
            timeout_seconds=1,
            poll_interval_seconds=0.1,
        )

        # monotonic: deadline=0+1=1, poll at 0.3 (<1 ok), poll at 1.5 (>=1 timeout)
        with mock.patch(
            "opik.evaluation.local_runner_task.time.monotonic",
            side_effect=_monotonic_side_effect(0.0, 0.3, 1.5),
        ):
            with pytest.raises(TimeoutError, match="did not complete within 1s"):
                task({"input": "test"})

    def test_call__no_job_id_in_location_header__raises_runtime_error(
        self,
        mock_rest_client,
        patch_get_client,
        patch_resolve_project_id,
        patch_sleep,
    ):
        mock_rest_client.runners.with_raw_response.create_job.return_value = (
            _make_create_job_response("")
        )

        task = LocalRunnerTask(
            project_name="My Project",
            agent_name="my_agent",
        )
        with pytest.raises(RuntimeError, match="Could not extract job ID"):
            task({"input": "test"})

    def test_call__project_id_resolved_once_across_multiple_calls(
        self,
        mock_rest_client,
        patch_get_client,
        patch_resolve_project_id,
        patch_sleep,
    ):
        mock_rest_client.runners.with_raw_response.create_job.return_value = (
            _make_create_job_response("/jobs/job-1")
        )
        mock_rest_client.runners.get_job.return_value = _make_job(
            "completed", result={"result": "ok"}
        )

        task = LocalRunnerTask(
            project_name="My Project",
            agent_name="my_agent",
        )
        task({"q": "first"})
        task({"q": "second"})

        patch_resolve_project_id.assert_called_once()

    def test_call__mask_id_passed_when_provided(
        self,
        mock_rest_client,
        patch_get_client,
        patch_resolve_project_id,
        patch_sleep,
    ):
        mock_rest_client.runners.with_raw_response.create_job.return_value = (
            _make_create_job_response("/jobs/job-mask")
        )
        mock_rest_client.runners.get_job.return_value = _make_job(
            "completed", result={"result": "ok"}
        )

        task = LocalRunnerTask(
            project_name="My Project",
            agent_name="my_agent",
            mask_id="mask-abc",
        )
        task({"input": "test"})

        call_kwargs = (
            mock_rest_client.runners.with_raw_response.create_job.call_args.kwargs
        )
        assert call_kwargs["mask_id"] == "mask-abc"

    def test_call__mask_id_omitted_when_none(
        self,
        mock_rest_client,
        patch_get_client,
        patch_resolve_project_id,
        patch_sleep,
    ):
        mock_rest_client.runners.with_raw_response.create_job.return_value = (
            _make_create_job_response("/jobs/job-nomask")
        )
        mock_rest_client.runners.get_job.return_value = _make_job(
            "completed", result={"result": "ok"}
        )

        task = LocalRunnerTask(
            project_name="My Project",
            agent_name="my_agent",
        )
        task({"input": "test"})

        call_kwargs = (
            mock_rest_client.runners.with_raw_response.create_job.call_args.kwargs
        )
        assert "mask_id" not in call_kwargs

    def test_call__completed_with_empty_result__returns_empty_output(
        self,
        mock_rest_client,
        patch_get_client,
        patch_resolve_project_id,
        patch_sleep,
    ):
        mock_rest_client.runners.with_raw_response.create_job.return_value = (
            _make_create_job_response("/jobs/job-empty")
        )
        mock_rest_client.runners.get_job.return_value = _make_job(
            "completed", result=None
        )

        task = LocalRunnerTask(
            project_name="My Project",
            agent_name="my_agent",
        )
        output = task({"input": "test"})

        assert output == {
            "input": {"input": "test"},
            "output": {},
        }

    def test_call__id_key_filtered_from_inputs(
        self,
        mock_rest_client,
        patch_get_client,
        patch_resolve_project_id,
        patch_sleep,
    ):
        mock_rest_client.runners.with_raw_response.create_job.return_value = (
            _make_create_job_response("/jobs/job-id-filter")
        )
        mock_rest_client.runners.get_job.return_value = _make_job(
            "completed", result={"result": "ok"}
        )

        task = LocalRunnerTask(
            project_name="My Project",
            agent_name="my_agent",
        )
        task({"id": "dataset-item-id", "topic": "AI"})

        call_kwargs = (
            mock_rest_client.runners.with_raw_response.create_job.call_args.kwargs
        )
        assert call_kwargs["inputs"] == {"topic": "AI"}
        assert "id" not in call_kwargs["inputs"]

    def test_call__job_transitions_from_pending_to_completed(
        self,
        mock_rest_client,
        patch_get_client,
        patch_resolve_project_id,
        patch_sleep,
    ):
        mock_rest_client.runners.with_raw_response.create_job.return_value = (
            _make_create_job_response("/jobs/job-transition")
        )
        mock_rest_client.runners.get_job.side_effect = [
            _make_job("pending"),
            _make_job("running"),
            _make_job("completed", result={"result": "done"}),
        ]

        task = LocalRunnerTask(
            project_name="My Project",
            agent_name="my_agent",
            timeout_seconds=1,
            poll_interval_seconds=0.1,
        )

        with mock.patch(
            "opik.evaluation.local_runner_task.time.monotonic",
            side_effect=_monotonic_side_effect(0.0, 0.1, 0.3),
        ):
            output = task({"input": "test"})

        assert output == {
            "input": {"input": "test"},
            "output": "done",
        }
        assert mock_rest_client.runners.get_job.call_count == 3
        assert patch_sleep.call_count == 2

    def test_call__job_cancelled__raises_runtime_error(
        self,
        mock_rest_client,
        patch_get_client,
        patch_resolve_project_id,
        patch_sleep,
    ):
        mock_rest_client.runners.with_raw_response.create_job.return_value = (
            _make_create_job_response("/jobs/job-cancelled")
        )
        mock_rest_client.runners.get_job.return_value = _make_job("cancelled")

        task = LocalRunnerTask(
            project_name="My Project",
            agent_name="my_agent",
        )
        with pytest.raises(RuntimeError, match="was cancelled"):
            task({"input": "test"})

    def test_call__timeout_less_than_poll_interval__uses_full_timeout(
        self,
        mock_rest_client,
        patch_get_client,
        patch_resolve_project_id,
        patch_sleep,
    ):
        mock_rest_client.runners.with_raw_response.create_job.return_value = (
            _make_create_job_response("/jobs/job-fast-timeout")
        )
        mock_rest_client.runners.get_job.return_value = _make_job("pending")

        task = LocalRunnerTask(
            project_name="My Project",
            agent_name="my_agent",
            timeout_seconds=1,
            poll_interval_seconds=5,
        )

        # monotonic: deadline=0+1=1, first poll at 0.5 (<1 sleep), second poll at 1.5 (>=1 timeout)
        with mock.patch(
            "opik.evaluation.local_runner_task.time.monotonic",
            side_effect=_monotonic_side_effect(0.0, 0.5, 1.5),
        ):
            with pytest.raises(TimeoutError, match="did not complete within 1s"):
                task({"input": "test"})

    def test_call__transient_network_error__retries_and_succeeds(
        self,
        mock_rest_client,
        patch_get_client,
        patch_resolve_project_id,
        patch_sleep,
    ):
        mock_rest_client.runners.with_raw_response.create_job.return_value = (
            _make_create_job_response("/jobs/job-flaky")
        )
        mock_rest_client.runners.get_job.side_effect = [
            httpx.ConnectError("connection refused"),
            _make_job("completed", result={"result": "recovered"}),
        ]

        task = LocalRunnerTask(
            project_name="My Project",
            agent_name="my_agent",
            timeout_seconds=1,
            poll_interval_seconds=0.1,
        )

        with mock.patch(
            "opik.evaluation.local_runner_task.time.monotonic",
            side_effect=_monotonic_side_effect(0.0, 0.1),
        ):
            output = task({"input": "test"})

        assert output == {
            "input": {"input": "test"},
            "output": "recovered",
        }
        assert mock_rest_client.runners.get_job.call_count == 2

    def test_call__result_without_result_key__returns_dict_directly(
        self,
        mock_rest_client,
        patch_get_client,
        patch_resolve_project_id,
        patch_sleep,
    ):
        mock_rest_client.runners.with_raw_response.create_job.return_value = (
            _make_create_job_response("/jobs/job-dict")
        )
        mock_rest_client.runners.get_job.return_value = _make_job(
            "completed", result={"answer": "42", "confidence": "high"}
        )

        task = LocalRunnerTask(
            project_name="My Project",
            agent_name="my_agent",
        )
        output = task({"input": "test"})

        assert output == {
            "input": {"input": "test"},
            "output": {"answer": "42", "confidence": "high"},
        }

    def test_init__poll_interval_zero__raises_value_error(self):
        with pytest.raises(ValueError, match="poll_interval_seconds must be positive"):
            LocalRunnerTask(
                project_name="My Project",
                agent_name="my_agent",
                poll_interval_seconds=0,
            )

    def test_init__poll_interval_negative__raises_value_error(self):
        with pytest.raises(ValueError, match="poll_interval_seconds must be positive"):
            LocalRunnerTask(
                project_name="My Project",
                agent_name="my_agent",
                poll_interval_seconds=-1,
            )

    def test_call__submit_network_error__raises_runtime_error(
        self,
        mock_rest_client,
        patch_get_client,
        patch_resolve_project_id,
        patch_sleep,
    ):
        mock_rest_client.runners.with_raw_response.create_job.side_effect = (
            httpx.ConnectError("connection refused")
        )

        task = LocalRunnerTask(
            project_name="My Project",
            agent_name="my_agent",
        )
        with pytest.raises(RuntimeError, match="network error"):
            task({"input": "test"})

    def test_call__distributed_trace_headers_forwarded(
        self,
        mock_rest_client,
        patch_get_client,
        patch_resolve_project_id,
        patch_sleep,
        patch_distributed_headers,
    ):
        mock_rest_client.runners.with_raw_response.create_job.return_value = (
            _make_create_job_response("/jobs/job-traced")
        )
        mock_rest_client.runners.get_job.return_value = _make_job(
            "completed", result={"result": "ok"}
        )

        task = LocalRunnerTask(
            project_name="My Project",
            agent_name="my_agent",
        )
        task({"input": "test"})

        call_kwargs = (
            mock_rest_client.runners.with_raw_response.create_job.call_args.kwargs
        )
        assert call_kwargs["metadata"] == {
            "opik_trace_id": "trace-abc",
            "opik_parent_span_id": "span-xyz",
        }

    def test_call__no_span_context__headers_omitted(
        self,
        mock_rest_client,
        patch_get_client,
        patch_resolve_project_id,
        patch_sleep,
    ):
        mock_rest_client.runners.with_raw_response.create_job.return_value = (
            _make_create_job_response("/jobs/job-no-context")
        )
        mock_rest_client.runners.get_job.return_value = _make_job(
            "completed", result={"result": "ok"}
        )

        # No patch_distributed_headers — get_distributed_trace_headers raises
        with mock.patch(
            "opik.evaluation.local_runner_task.opik_context.get_distributed_trace_headers",
            side_effect=Exception("no span context"),
        ):
            task = LocalRunnerTask(
                project_name="My Project",
                agent_name="my_agent",
            )
            task({"input": "test"})

        call_kwargs = (
            mock_rest_client.runners.with_raw_response.create_job.call_args.kwargs
        )
        assert "metadata" not in call_kwargs

