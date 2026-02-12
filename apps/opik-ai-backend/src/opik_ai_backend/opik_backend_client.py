"""Direct HTTP client for Opik backend.

This client passes through session cookies and workspace headers from the frontend
to the Opik backend, which handles authentication itself via AuthFilter.
"""

import json as json_module
from typing import Optional

import aiohttp

from .config import settings
from .logger_config import logger


class OpikBackendClient:
    """HTTP client for direct calls to Opik backend."""

    def __init__(
        self,
        session: aiohttp.ClientSession,
        session_token: Optional[str] = None,
        workspace: Optional[str] = None,
    ):
        """
        Initialize the Opik backend client.

        Args:
            session: Shared aiohttp ClientSession with base_url already configured
            session_token: Session token cookie value (optional, for cloud mode)
            workspace: Workspace name (optional, for cloud mode)
        """
        self._session = session
        self.session_token = session_token
        self.workspace = workspace

    def _get_cookies(self) -> Optional[dict[str, str]]:
        """Build cookies for requests (per-call, not session-level)."""
        if self.session_token:
            return {"sessionToken": self.session_token}
        return None

    def _get_headers(self) -> dict[str, str]:
        """Build headers for requests (per-call, not session-level)."""
        headers = {}
        if self.workspace:
            headers["Comet-Workspace"] = self.workspace
        return headers

    async def get_trace(self, trace_id: str) -> dict:
        """
        Get trace by ID.

        Args:
            trace_id: The trace ID

        Returns:
            Trace data as dict

        Raises:
            aiohttp.ClientResponseError: If the request fails
        """
        url = f"/v1/private/traces/{trace_id}"
        async with self._session.get(
            url,
            cookies=self._get_cookies(),
            headers=self._get_headers(),
            timeout=aiohttp.ClientTimeout(total=settings.opik_backend_timeout),
        ) as response:
            response.raise_for_status()
            return await response.json()

    async def get_project(self, project_id: str) -> dict:
        """
        Get project by ID.

        Args:
            project_id: The project ID (UUID)

        Returns:
            Project data as dict

        Raises:
            aiohttp.ClientResponseError: If the request fails
        """
        url = f"/v1/private/projects/{project_id}"
        async with self._session.get(
            url,
            cookies=self._get_cookies(),
            headers=self._get_headers(),
            timeout=aiohttp.ClientTimeout(total=settings.opik_backend_timeout),
        ) as response:
            response.raise_for_status()
            return await response.json()

    async def get_span(self, span_id: str) -> dict:
        """
        Get a single span by ID.

        Args:
            span_id: The span ID

        Returns:
            Span data as dict

        Raises:
            aiohttp.ClientResponseError: If the request fails
        """
        url = f"/v1/private/spans/{span_id}"
        async with self._session.get(
            url,
            cookies=self._get_cookies(),
            headers=self._get_headers(),
            timeout=aiohttp.ClientTimeout(total=settings.opik_backend_timeout),
        ) as response:
            response.raise_for_status()
            return await response.json()

    async def search_spans(
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
            aiohttp.ClientResponseError: If the request fails
        """
        url = "/v1/private/spans/search"
        payload = {
            "project_id": project_id,
            "trace_id": trace_id,
            "truncate": truncate,
        }
        # This endpoint returns chunked NDJSON as application/octet-stream
        headers = self._get_headers()
        headers["Accept"] = "application/octet-stream"

        async with self._session.post(
            url,
            headers=headers,
            cookies=self._get_cookies(),
            json=payload,
            timeout=aiohttp.ClientTimeout(total=settings.opik_backend_timeout),
        ) as response:
            response.raise_for_status()
            text = await response.text()

        spans = []
        for line in text.strip().split("\r\n"):
            line = line.strip()
            if line:
                spans.append(json_module.loads(line))
        return spans

    async def get_project_name_from_trace(self, trace_id: str) -> str:
        """
        Get project name from trace ID.

        This combines get_trace + get_project calls.
        Only needed for delete_feedback operation which requires projectName.

        Args:
            trace_id: The trace ID

        Returns:
            Project name

        Raises:
            aiohttp.ClientResponseError: If the request fails
        """
        trace = await self.get_trace(trace_id)
        project_id = trace["project_id"]
        project = await self.get_project(project_id)
        return project["name"]

    async def close_thread(self, thread_id: str, project_id: str) -> None:
        """
        Close a thread.

        Args:
            thread_id: The thread ID
            project_id: The project ID (UUID)

        Raises:
            aiohttp.ClientResponseError: If the request fails
        """
        url = "/v1/private/traces/threads/close"
        payload = {
            "project_id": project_id,
            "thread_id": thread_id,
        }
        async with self._session.put(
            url,
            cookies=self._get_cookies(),
            headers=self._get_headers(),
            json=payload,
            timeout=aiohttp.ClientTimeout(total=settings.opik_backend_timeout),
        ) as response:
            response.raise_for_status()
        logger.info(f"Successfully closed thread {thread_id}")

    async def log_thread_feedback_scores(self, scores: list[dict]) -> None:
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
            aiohttp.ClientResponseError: If the request fails
        """
        url = "/v1/private/traces/threads/feedback-scores"
        payload = {"scores": scores}
        async with self._session.put(
            url,
            cookies=self._get_cookies(),
            headers=self._get_headers(),
            json=payload,
            timeout=aiohttp.ClientTimeout(total=settings.opik_backend_timeout),
        ) as response:
            response.raise_for_status()
        logger.info(f"Successfully logged {len(scores)} feedback scores")

    async def delete_thread_feedback_scores(
        self, project_name: str, thread_id: str, names: list[str]
    ) -> None:
        """
        Delete feedback scores for a thread.

        Args:
            project_name: The project name (required by backend)
            thread_id: The thread ID
            names: List of score names to delete

        Raises:
            aiohttp.ClientResponseError: If the request fails
        """
        url = "/v1/private/traces/threads/feedback-scores/delete"
        payload = {
            "project_name": project_name,
            "thread_id": thread_id,
            "names": names,
        }
        async with self._session.post(
            url,
            cookies=self._get_cookies(),
            headers=self._get_headers(),
            json=payload,
            timeout=aiohttp.ClientTimeout(total=settings.opik_backend_timeout),
        ) as response:
            response.raise_for_status()
        logger.info(
            f"Successfully deleted feedback scores {names} for thread {thread_id}"
        )
