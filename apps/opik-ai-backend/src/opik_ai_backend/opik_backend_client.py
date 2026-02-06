"""Direct HTTP client for Opik backend.

This client passes through session cookies and workspace headers from the frontend
to the Opik backend, which handles authentication itself via AuthFilter.
"""

from typing import Optional

import requests

from .logger_config import logger


class OpikBackendClient:
    """HTTP client for direct calls to Opik backend."""

    def __init__(
        self,
        base_url: str,
        session_token: Optional[str] = None,
        workspace: Optional[str] = None,
    ):
        """
        Initialize the Opik backend client.

        Args:
            base_url: Base URL of the Opik backend (e.g., "http://backend:8080")
            session_token: Session token cookie value (optional, for cloud mode)
            workspace: Workspace name (optional, for cloud mode)
        """
        self.base_url = base_url.rstrip("/")
        self.session_token = session_token
        self.workspace = workspace

    def _get_headers(self) -> dict[str, str]:
        """Build headers for requests."""
        headers = {
            "Accept": "application/json",
            "Content-Type": "application/json",
        }
        if self.workspace:
            headers["Comet-Workspace"] = self.workspace
        return headers

    def _get_cookies(self) -> Optional[dict[str, str]]:
        """Build cookies for requests."""
        if self.session_token:
            return {"sessionToken": self.session_token}
        return None

    def get_trace(self, trace_id: str) -> dict:
        """
        Get trace by ID.

        Args:
            trace_id: The trace ID

        Returns:
            Trace data as dict

        Raises:
            requests.HTTPError: If the request fails
        """
        url = f"{self.base_url}/v1/private/traces/{trace_id}"
        response = requests.get(
            url,
            headers=self._get_headers(),
            cookies=self._get_cookies(),
            timeout=30,
        )
        response.raise_for_status()
        return response.json()

    def get_project(self, project_id: str) -> dict:
        """
        Get project by ID.

        Args:
            project_id: The project ID (UUID)

        Returns:
            Project data as dict

        Raises:
            requests.HTTPError: If the request fails
        """
        url = f"{self.base_url}/v1/private/projects/{project_id}"
        response = requests.get(
            url,
            headers=self._get_headers(),
            cookies=self._get_cookies(),
            timeout=30,
        )
        response.raise_for_status()
        return response.json()

    def get_span(self, span_id: str) -> dict:
        """
        Get a single span by ID.

        Args:
            span_id: The span ID

        Returns:
            Span data as dict

        Raises:
            requests.HTTPError: If the request fails
        """
        url = f"{self.base_url}/v1/private/spans/{span_id}"
        response = requests.get(
            url,
            headers=self._get_headers(),
            cookies=self._get_cookies(),
            timeout=30,
        )
        response.raise_for_status()
        return response.json()

    def search_spans(
        self, project_id: str, trace_id: str, truncate: bool = False
    ) -> list[dict]:
        """
        Search spans by trace ID.

        Args:
            project_id: The project ID (UUID)
            trace_id: The trace ID
            truncate: Whether to truncate large fields (default: False)

        Returns:
            List of span dicts

        Raises:
            requests.HTTPError: If the request fails
        """
        url = f"{self.base_url}/v1/private/spans/search"
        payload = {
            "project_id": project_id,
            "trace_id": trace_id,
            "truncate": truncate,
        }
        headers = self._get_headers()
        # This endpoint returns chunked NDJSON as application/octet-stream
        headers["Accept"] = "application/octet-stream"
        response = requests.post(
            url,
            headers=headers,
            cookies=self._get_cookies(),
            json=payload,
            timeout=30,
        )
        response.raise_for_status()

        import json as json_module

        spans = []
        for line in response.text.strip().split("\r\n"):
            line = line.strip()
            if line:
                spans.append(json_module.loads(line))
        return spans

    def get_project_name_from_trace(self, trace_id: str) -> str:
        """
        Get project name from trace ID.

        This combines get_trace + get_project calls.
        Only needed for delete_feedback operation which requires projectName.

        Args:
            trace_id: The trace ID

        Returns:
            Project name

        Raises:
            requests.HTTPError: If the request fails
        """
        trace = self.get_trace(trace_id)
        project_id = trace["project_id"]
        project = self.get_project(project_id)
        return project["name"]

    def close_thread(self, thread_id: str, project_id: str) -> None:
        """
        Close a thread.

        Args:
            thread_id: The thread ID
            project_id: The project ID (UUID)

        Raises:
            requests.HTTPError: If the request fails
        """
        url = f"{self.base_url}/v1/private/traces/threads/close"
        payload = {
            "project_id": project_id,
            "thread_id": thread_id,
        }
        response = requests.put(
            url,
            headers=self._get_headers(),
            cookies=self._get_cookies(),
            json=payload,
            timeout=30,
        )
        response.raise_for_status()
        logger.info(f"Successfully closed thread {thread_id}")

    def log_thread_feedback_scores(self, scores: list[dict]) -> None:
        """
        Log feedback scores for threads.

        Args:
            scores: List of score dicts, each containing:
                - project_id (str): Project ID
                - thread_id (str): Thread ID
                - name (str): Score name
                - value (float): Score value
                - source (str): Score source (e.g., "ui")

        Raises:
            requests.HTTPError: If the request fails
        """
        url = f"{self.base_url}/v1/private/traces/threads/feedback-scores"
        payload = {"scores": scores}
        response = requests.put(
            url,
            headers=self._get_headers(),
            cookies=self._get_cookies(),
            json=payload,
            timeout=30,
        )
        response.raise_for_status()
        logger.info(f"Successfully logged {len(scores)} feedback scores")

    def delete_thread_feedback_scores(
        self, project_name: str, thread_id: str, names: list[str]
    ) -> None:
        """
        Delete feedback scores for a thread.

        Args:
            project_name: The project name (required by backend)
            thread_id: The thread ID
            names: List of score names to delete

        Raises:
            requests.HTTPError: If the request fails
        """
        url = f"{self.base_url}/v1/private/traces/threads/feedback-scores/delete"
        payload = {
            "project_name": project_name,
            "thread_id": thread_id,
            "names": names,
        }
        response = requests.post(
            url,
            headers=self._get_headers(),
            cookies=self._get_cookies(),
            json=payload,
            timeout=30,
        )
        response.raise_for_status()
        logger.info(
            f"Successfully deleted feedback scores {names} for thread {thread_id}"
        )
