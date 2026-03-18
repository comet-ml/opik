import logging
import time
from typing import Any, Dict, Optional

from ..api_objects import opik_client, rest_helpers
from ..rest_api import client as rest_api_client

LOGGER = logging.getLogger(__name__)


class LocalRunnerTask:
    """
    A callable task that submits jobs to the local runner and polls for completion.
    Conforms to LLMTask protocol for use with evaluate() and EvaluationSuite.

    Args:
        project_name: The project to submit jobs under.
        agent_name: The agent to execute the job.
        mask_id: Optional agent config override mask.
        timeout_seconds: Max time to wait for job completion.
        poll_interval_seconds: Interval between status polls.
    """

    def __init__(
        self,
        project_name: str,
        agent_name: str,
        mask_id: Optional[str] = None,
        timeout_seconds: int = 120,
        poll_interval_seconds: int = 2,
    ) -> None:
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

        resp = rest_client.runners.with_raw_response.create_job(**kwargs)
        job_id = resp.headers.get("location", "").rsplit("/", 1)[-1]
        if not job_id:
            raise RuntimeError("Could not extract job ID from Location header")
        return job_id

    def _wait_for_job(self, job_id: str) -> Dict[str, Any]:
        rest_client = self._get_rest_client()
        iterations = self._timeout_seconds // self._poll_interval_seconds

        for _ in range(iterations):
            job = rest_client.runners.get_job(job_id)
            if job.status == "completed":
                return dict(job.result) if job.result else {}
            if job.status == "failed":
                raise RuntimeError(f"Job {job_id} failed: {job.error}")
            time.sleep(self._poll_interval_seconds)

        raise TimeoutError(
            f"Job {job_id} did not complete within {self._timeout_seconds}s"
        )

    def __call__(self, item: Dict[str, Any]) -> Dict[str, Any]:
        inputs = {k: v for k, v in item.items() if k != "id"}
        result = self._wait_for_job(self._submit_job(inputs))
        return {
            "input": item,
            "output": result.get("result", str(result)),
        }
