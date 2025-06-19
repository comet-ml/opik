from typing import List, Optional

import opik
from opik.rest_api import TraceThread
from opik.types import FeedbackScoreDict


class ThreadsClient:
    def __init__(self, client: "opik.Opik"):
        self._opik_client = client

    def search_threads(
        self,
        project_name: Optional[str] = None,
        filter_string: Optional[str] = None,
        max_results: int = 1000,
    ) -> List[TraceThread]:
        raise NotImplementedError

    def log_threads_feedback_scores(
        self, scores: List[FeedbackScoreDict], project_name: Optional[str] = None
    ) -> None:
        raise NotImplementedError
