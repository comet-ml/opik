from typing import List, Optional

import opik
from opik.rest_api import TraceThread
from opik.types import FeedbackScoreDict


class ThreadsClient:
    """
    Client for managing and interacting with conversation threads.

    This class provides methods for searching threads and logging feedback scores
    related to threads using an underlying client instance. It is intended to be
    used in scenarios where thread management and feedback tracking are required.

    Args:
        client: Instance of the underlying OPIK client.
    """

    def __init__(self, client: "opik.Opik"):
        self._opik_client = client

    def search_threads(
        self,
        project_name: Optional[str] = None,
        filter_string: Optional[str] = None,
        max_results: int = 1000,
    ) -> List[TraceThread]:
        """Search for threads in a given project based on specific criteria.

        This method retrieves a list of TraceThread objects that match the specified
        filter criteria. It takes an optional project name, a filter string to narrow
        down the results, and a maximum number of threads to return.

        Args:
            project_name:
                The name of the project to search the threads for. If None, the search
                will include threads from all projects.
            filter_string:
                A string used to filter threads based on specific conditions. The
                format and details of the filter depend on the implementation.
            max_results:
                The maximum number of threads to retrieve. The default value is 1000
                if not specified.

        Returns:
            List[TraceThread]: A list of TraceThread objects that match the search
            criteria.

        Example:
            >>> from opik import Opik
            >>> client = Opik(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME")
            >>> thread_id = "your_thread_id"
            >>> threads = client.get_threads_client().search_threads(
            >>>     project_name="Demo Project",
            >>>     filter_string=f'id = {thread_id}',
            >>>     max_results=10)
        """
        raise NotImplementedError

    def log_threads_feedback_scores(
        self, scores: List[FeedbackScoreDict], project_name: Optional[str] = None
    ) -> None:
        """
        Logs feedback scores for threads in a specific project. This method processes the given
        feedback scores and associates them with the specified project if a project name is
        provided. It is designed to handle multiple scores in a structured manner.

        Args:
            scores: A list of dictionaries containing feedback scores
                for threads to be logged.
            project_name: The name of the project to associate with the logged
                scores. If not provided, the scores won't be associated with any specific project.
        """
        raise NotImplementedError

    @property
    def opik_client(self) -> "opik.Opik":
        return self._opik_client
