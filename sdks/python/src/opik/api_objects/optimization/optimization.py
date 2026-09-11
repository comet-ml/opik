import logging
from typing import Any, Optional, Literal

from opik.rest_api import client as rest_api_client
from opik.rest_api import types as rest_api_types
from opik.rest_api.core.api_error import ApiError

LOGGER = logging.getLogger(__name__)


class Optimization:
    def __init__(
        self,
        id: str,
        rest_client: rest_api_client.OpikApi,
        project_name: Optional[str] = None,
    ) -> None:
        self._id = id
        self._rest_client = rest_client
        self._project_name = project_name

    @property
    def id(self) -> str:
        return self._id

    @property
    def project_name(self) -> Optional[str]:
        return self._project_name

    def update(
        self,
        name: Optional[str] = None,
        status: Optional[
            Literal["running", "completed", "cancelled", "initialized", "error"]
        ] = None,
        error_info: Optional[dict] = None,
    ) -> None:
        LOGGER.debug(
            f"Updating optimization {self.id} with name {name} and status {status}"
        )
        # Only forward error_info when supplied; passing None would serialize an
        # explicit null and could clobber a previously-persisted reason on a
        # subsequent non-error update.
        extra = {"error_info": error_info} if error_info is not None else {}
        try:
            self._rest_client.optimizations.update_optimizations_by_id(
                id=self.id,
                name=name,
                status=status,
                **extra,
            )
        except TypeError:
            # An older installed opik whose typed ``update_optimizations_by_id``
            # predates the ``error_info`` field rejects that kwarg with a
            # TypeError. Fall back to the SDK's pre-configured httpx client
            # (accepts snake_case fields, ignores unknown ones) so the update —
            # crucially the ``status`` transition on the error path — still
            # lands instead of throwing while trying to record a failure and
            # leaving the run stuck. Mirrors the python-backend worker's
            # status_manager fallback. Once the SDK ships ``error_info`` this
            # branch is never exercised.
            if not extra:
                raise
            LOGGER.debug(
                "Installed opik SDK lacks the 'error_info' update field; "
                "sending the optimization update via the raw REST client."
            )
            body: dict = {"error_info": error_info}
            if name is not None:
                body["name"] = name
            if status is not None:
                body["status"] = status
            response = self._rest_client.optimizations._raw_client._client_wrapper.httpx_client.request(
                f"v1/private/optimizations/{self.id}",
                method="PUT",
                json=body,
            )
            if response.status_code >= 300:
                # Preserve the generated client's error contract: callers that
                # `except ApiError` / inspect status_code must see the same
                # exception type as the typed path, not a leaked
                # httpx.HTTPStatusError from raise_for_status().
                error_body: Any
                try:
                    error_body = response.json()
                except Exception:
                    error_body = response.text
                raise ApiError(
                    status_code=response.status_code,
                    headers=dict(response.headers),
                    body=error_body,
                )

    def fetch_content(self) -> rest_api_types.OptimizationPublic:
        LOGGER.debug(f"Fetching optimization data {self.id}")
        return self._rest_client.optimizations.get_optimization_by_id(id=self.id)
