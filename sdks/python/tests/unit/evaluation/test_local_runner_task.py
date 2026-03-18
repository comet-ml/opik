from unittest import mock

import pytest

from opik.evaluation.local_runner_task import LocalRunnerTask


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
            timeout_seconds=4,
            poll_interval_seconds=2,
        )
        with pytest.raises(TimeoutError, match="did not complete within 4s"):
            task({"input": "test"})

        assert mock_rest_client.runners.get_job.call_count == 2

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
            "output": "{}",
        }
