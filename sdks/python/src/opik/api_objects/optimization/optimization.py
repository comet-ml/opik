import logging
from typing import Optional, Literal

from opik.rest_api import client as rest_api_client
from opik.rest_api import types as rest_api_types

LOGGER = logging.getLogger(__name__)


class Optimization:
    def __init__(
        self,
        id: str,
        rest_client: rest_api_client.OpikApi,
    ) -> None:
        self._id = id
        self._rest_client = rest_client

    @property
    def id(self) -> str:
        return self._id

    def update(
        self,
        name: Optional[str] = None,
        status: Optional[Literal["running", "completed", "cancelled"]] = None,
    ) -> None:
        LOGGER.debug(
            f"Updating optimization {self.id} with name {name} and status {status}"
        )
        self._rest_client.optimizations.update_optimizations_by_id(
            id=self.id,
            name=name,
            status=status,
        )

    def fetch_content(self) -> rest_api_types.OptimizationPublic:
        LOGGER.debug(f"Fetching optimization data {self.id}")
        return self._rest_client.optimizations.get_optimization_by_id(id=self.id)
