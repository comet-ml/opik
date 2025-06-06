import logging
from typing import Optional, Literal, Union, Dict, Any, Type
from types import TracebackType

from opik.rest_api import client as rest_api_client
from opik.rest_api import types as rest_api_types
from opik.api_objects.opik_client import Opik

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


class OptimizationContextManager:
    """
    Context manager for handling optimization lifecycle.
    Automatically updates optimization status to "completed" or "cancelled" based on context exit.
    """

    def __init__(
        self,
        client: "Opik",
        dataset_name: str,
        objective_name: str,
        name: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ):
        """
        Initialize the optimization context.

        Args:
            client: The Opik client instance
            dataset_name: Name of the dataset for optimization
            objective_name: Name of the optimization objective
            name: Optional name for the optimization
            metadata: Optional metadata for the optimization
        """
        self.client = client
        self.dataset_name = dataset_name
        self.objective_name = objective_name
        self.name = name
        self.metadata = metadata
        self.optimization: Union[Optimization, None] = None

    def __enter__(self) -> Union[str, None]:
        """Create and return the optimization ID."""
        try:
            self.optimization = self.client.create_optimization(
                dataset_name=self.dataset_name,
                objective_name=self.objective_name,
                name=self.name,
                metadata=self.metadata,
            )
            if self.optimization:
                return self.optimization.id
            else:
                return None
        except Exception:
            LOGGER.warning(
                "Opik server does not support optimizations. Please upgrade opik."
            )
            LOGGER.warning("Continuing without Opik optimization tracking.")
            return None

    def __exit__(
        self,
        exc_type: Optional[Type[BaseException]],
        exc_val: Optional[BaseException],
        exc_tb: Optional[TracebackType],
    ) -> Literal[False]:
        """Update optimization status based on context exit."""
        if self.optimization is None:
            return False

        try:
            if exc_type is None:
                self.optimization.update(status="completed")
            else:
                self.optimization.update(status="cancelled")
        except Exception as e:
            LOGGER.error(f"Failed to update optimization status: {e}")

        return False
