import logging
import time
from typing import Any, Dict, Optional

import httpx

from ..api_objects import opik_client, rest_helpers
from ..rest_api import client as rest_api_client

LOGGER = logging.getLogger(__name__)

DEFAULT_TIMEOUT_SECONDS = 120
DEFAULT_POLL_INTERVAL_SECONDS = 0.5


class LocalRunnerTask:
    """
    A callable task that submits jobs to the local runner and polls for completion.
    Conforms to the ``LLMTask`` protocol (``Callable[[Dict[str, Any]], Dict[str, Any]]``)
    for use with ``evaluate()`` and ``EvaluationSuite``.

    When called, the instance:

    1. Filters the ``"id"`` key from the input dict (dataset-item artefact).
    2. Submits a job to the local runner via the REST API.
    3. Polls until the job reaches a terminal status or the timeout expires.
    4. Returns ``{"input": <original item>, "output": <job result>}``.

    Failures raise ``RuntimeError``; timeouts raise ``TimeoutError``.

    Args:
        project_name: The project to submit jobs under.
        agent_name: The agent to execute the job.
        mask_id: Optional agent config override mask.
        timeout_seconds: Max time to wait for job completion (default 120).
        poll_interval_seconds: Interval between status polls (default 0.5).
    """

    def __init__(
        self,
        project_name: str,
        agent_name: str,
        mask_id: Optional[str] = None,
        timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
        poll_interval_seconds: float = DEFAULT_POLL_INTERVAL_SECONDS,
    ) -> None:
        if poll_interval_seconds <= 0:
            raise ValueError("poll_interval_seconds must be positive")

        self._project_name = project_name
        self._agent_name = agent_name
        self._mask_id = mask_id
        self._timeout_seconds = timeout_seconds
        self._poll_interval_seconds = poll_interval_seconds
        self._project_id: Optional[str] = None

    def _get_rest_client(self) -> rest_api_client.OpikApi:
        return opik_client.get_client_cached().rest_client

    def _resolve_project_id(self) -> str:
        if self._project_id is None:
            self._project_id = rest_helpers.resolve_project_id_by_name(
                self._get_rest_client(), self._project_name
            )
        return self._project_id

    def _submit_job(self, inputs: Dict[str, Any]) -> str:
        rest_client = self._get_rest_client()
        project_id = self._resolve_project_id()

        kwargs: Dict[str, Any] = {
            "agent_name": self._agent_name,
            "project_id": project_id,
            "inputs": inputs,
        }
        if self._mask_id is not None:
            kwargs["mask_id"] = self._mask_id

        try:
            # create_job raises for non-2xx responses internally
            resp = rest_client.runners.with_raw_response.create_job(**kwargs)
        except (httpx.ConnectError, httpx.TimeoutException) as exc:
            raise RuntimeError(
                f"Failed to submit job due to network error: {exc}"
            ) from exc

        job_id = resp.headers.get("location", "").rsplit("/", 1)[-1]
        if not job_id:
            raise RuntimeError("Could not extract job ID from Location header")
        return job_id

    def _wait_for_job(self, job_id: str) -> Dict[str, Any]:
        rest_client = self._get_rest_client()
        deadline = time.monotonic() + self._timeout_seconds

        while True:
            try:
                job = rest_client.runners.get_job(job_id)
            except (httpx.ConnectError, httpx.TimeoutException) as exc:
                LOGGER.warning(
                    "Transient network error polling job %s: %s", job_id, exc
                )
                if time.monotonic() >= deadline:
                    raise TimeoutError(
                        f"Job {job_id} did not complete within {self._timeout_seconds}s"
                    ) from exc
                time.sleep(self._poll_interval_seconds)
                continue

            if job.status == "completed":
                return dict(job.result) if job.result else {}
            if job.status == "failed":
                raise RuntimeError(f"Job {job_id} failed: {job.error}")
            if job.status == "cancelled":
                raise RuntimeError(f"Job {job_id} was cancelled")

            if time.monotonic() >= deadline:
                raise TimeoutError(
                    f"Job {job_id} did not complete within {self._timeout_seconds}s"
                )
            time.sleep(self._poll_interval_seconds)

    def __call__(self, item: Dict[str, Any]) -> Dict[str, Any]:
        # Filter out "id" — it's a dataset-item identifier, not an agent input
        inputs = {k: v for k, v in item.items() if k != "id"}
        result = self._wait_for_job(self._submit_job(inputs))
        output = result.get("result", result)
        return {
            "input": item,
            "output": output,
        }
