from typing import Optional

from opik.api_objects import opik_client


class ThreadsEvaluationEngine:
    def __init__(
        self,
        client: opik_client.Opik,
        project_name: Optional[str],
        workers: int,
    ) -> None:
        self._client = client
        self._project_name = project_name
        self._workers = workers
