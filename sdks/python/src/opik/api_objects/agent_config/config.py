import typing

from opik.rest_api import client as rest_client
from .blueprint import Blueprint
from .client import ConfigClient


class AgentConfig:
    """Project-level agent config entity."""

    def __init__(
        self,
        project_name: str,
        config_client: ConfigClient,
        rest_client_: rest_client.OpikApi,
    ) -> None:
        self._project_name = project_name
        self._config_client = config_client
        self._rest_client = rest_client_

    @property
    def project_name(self) -> str:
        return self._project_name

    def try_get_blueprint(
        self,
        env: typing.Optional[str] = None,
        mask_id: typing.Optional[str] = None,
        field_types: typing.Optional[typing.Dict[str, typing.Any]] = None,
    ) -> typing.Optional[Blueprint]:
        raw = self._config_client.try_get_blueprint(
            project_name=self._project_name,
            env=env,
            mask_id=mask_id,
        )
        if raw is None:
            return None
        return Blueprint(
            raw_blueprint=raw,
            field_types=field_types,
            rest_client_=self._rest_client,
        )

    def get_blueprint(
        self,
        env: typing.Optional[str] = None,
        mask_id: typing.Optional[str] = None,
        field_types: typing.Optional[typing.Dict[str, typing.Any]] = None,
    ) -> Blueprint:
        raw = self._config_client.get_blueprint(
            project_name=self._project_name,
            env=env,
            mask_id=mask_id,
        )
        return Blueprint(
            raw_blueprint=raw,
            field_types=field_types,
            rest_client_=self._rest_client,
        )

    def get_blueprint_by_id(
        self,
        blueprint_id: str,
        field_types: typing.Optional[typing.Dict[str, typing.Any]] = None,
    ) -> Blueprint:
        raw = self._config_client.get_blueprint_by_id(blueprint_id)
        return Blueprint(
            raw_blueprint=raw,
            field_types=field_types,
            rest_client_=self._rest_client,
        )

    def create_blueprint(
        self,
        parameters: typing.Optional[typing.Dict[str, typing.Any]] = None,
        fields_with_values: typing.Optional[
            typing.Dict[str, typing.Tuple[typing.Any, typing.Any]]
        ] = None,
        description: typing.Optional[str] = None,
        field_types: typing.Optional[typing.Dict[str, typing.Any]] = None,
    ) -> Blueprint:
        if fields_with_values is None:
            fields_with_values = {
                k: (type(v), v) for k, v in (parameters or {}).items()
            }
        raw = self._config_client.create_config(
            fields_with_values=fields_with_values,
            project_name=self._project_name,
            description=description,
        )
        return Blueprint(
            raw_blueprint=raw,
            field_types=field_types,
            rest_client_=self._rest_client,
        )

    def tag_bluepring_with_env(self, env: str, blueprint_id: str) -> None:
        self._config_client.tag_blueprint_with_env(
            project_name=self._project_name,
            env=env,
            blueprint_id=blueprint_id,
        )

    def create_mask(
        self,
        parameters: typing.Optional[typing.Dict[str, typing.Any]] = None,
        fields_with_values: typing.Optional[
            typing.Dict[str, typing.Tuple[typing.Any, typing.Any]]
        ] = None,
        description: typing.Optional[str] = None,
    ) -> str:
        """Create a mask and return its ID. Apply via get_blueprint(mask_id=...)."""
        if fields_with_values is None:
            fields_with_values = {
                k: (type(v), v) for k, v in (parameters or {}).items()
            }
        return self._config_client.create_mask(
            fields_with_values=fields_with_values,
            project_name=self._project_name,
            description=description,
        )
