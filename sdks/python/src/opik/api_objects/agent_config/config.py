import copy
import typing

from .client import ConfigClient, ConfigData


class AgentConfig:
    """Programmatic handle for an Opik agent config (CRUD without the decorator)."""

    def __init__(
        self,
        parameters: typing.Dict[str, typing.Any],
        project_name: typing.Optional[str] = None,
        description: typing.Optional[str] = None,
    ):
        self._parameters = parameters
        self._project_name = project_name
        self._description = description
        self._blueprint_id: typing.Optional[str] = None
        self._values: typing.Dict[str, typing.Any] = {}
        self._sync_with_backend()

    def _sync_with_backend(self) -> None:
        from opik.api_objects import opik_client

        client = opik_client.get_client_cached()
        project_name = self._project_name or client._project_name
        config_client = ConfigClient(client.rest_client)

        fields_with_values: typing.Dict[str, typing.Tuple[typing.Any, typing.Any]] = {}
        for key, value in self._parameters.items():
            py_type = type(value)
            fields_with_values[key] = (py_type, value)

        config_data = config_client.create_config(
            fields_with_values=fields_with_values,
            project_name=project_name,
            description=self._description,
        )
        self._apply_config_data(config_data)

    def _apply_config_data(self, config_data: ConfigData) -> None:
        self._blueprint_id = config_data.blueprint_id
        self._values = config_data.values

    @classmethod
    def from_backend_data(
        cls,
        config_data: ConfigData,
    ) -> "AgentConfig":
        obj = cls.__new__(cls)
        obj._parameters = {}
        obj._project_name = None
        obj._description = config_data.description
        obj._blueprint_id = config_data.blueprint_id
        obj._values = config_data.values
        return obj

    @property
    def blueprint_id(self) -> typing.Optional[str]:
        return self._blueprint_id

    @property
    def values(self) -> typing.Dict[str, typing.Any]:
        return copy.deepcopy(self._values)

    def get(self, key: str, default: typing.Any = None) -> typing.Any:
        return self._values.get(key, default)

    def __getitem__(self, key: str) -> typing.Any:
        return self._values[key]

    def keys(self) -> typing.KeysView[str]:
        return self._values.keys()

    def update(
        self,
        values: typing.Dict[str, typing.Any],
        project_id: typing.Optional[str] = None,
        description: typing.Optional[str] = None,
    ) -> None:
        from opik.api_objects import opik_client

        client = opik_client.get_client_cached()
        project_name = self._project_name or client._project_name
        config_client = ConfigClient(client.rest_client)

        fields_with_values: typing.Dict[str, typing.Tuple[typing.Any, typing.Any]] = {
            key: (type(value), value) for key, value in values.items()
        }
        config_data = config_client.create_config(
            fields_with_values=fields_with_values,
            project_name=project_name,
            project_id=project_id,
            description=description,
        )
        self._apply_config_data(config_data)

    def create_mask(
        self,
        values: typing.Dict[str, typing.Any],
        project_id: typing.Optional[str] = None,
        description: typing.Optional[str] = None,
    ) -> "AgentConfig":
        """Create a mask that overrides a subset of this config's keys.

        Primarily for internal use. Works the same as :meth:`update` but sends
        the values under the ``"mask"`` key in the API request body instead of
        ``"blueprint"``.  A mask covers only the keys you supply, leaving all
        other blueprint values unchanged.  Useful for creating A/B experiment
        arms or feature-flag overrides without replacing the whole blueprint.

        Args:
            values: Mapping of key → value for the keys to override.  Types are
                inferred from the runtime types of the supplied values.
            project_id: Optional explicit project ID; forwarded to the backend.
            description: Optional human-readable description stored with the mask.

        Returns:
            A new :class:`AgentConfig` instance whose values reflect the mask.
        """
        from opik.api_objects import opik_client

        client = opik_client.get_client_cached()
        project_name = self._project_name or client._project_name
        config_client = ConfigClient(client.rest_client)

        fields_with_values: typing.Dict[str, typing.Tuple[typing.Any, typing.Any]] = {
            key: (type(value), value) for key, value in values.items()
        }
        config_data = config_client.create_mask(
            fields_with_values=fields_with_values,
            project_name=project_name,
            project_id=project_id,
            description=description,
        )
        return AgentConfig.from_backend_data(config_data)

    @classmethod
    def get_blueprint(
        cls,
        project_name: typing.Optional[str] = None,
        env: typing.Optional[str] = None,
        mask_id: typing.Optional[str] = None,
    ) -> "AgentConfig":
        """Retrieve an existing blueprint from the backend without creating one.

        At most one of *env* and *mask_id* should be provided. When neither is
        given the latest active blueprint for the project is returned.

        Args:
            project_name: Project whose blueprint to fetch.  Defaults to the
                project configured on the global Opik client.
            env: Return the blueprint pinned to this environment label (e.g.
                ``"prod"``).
            mask_id: Return the blueprint resolved through this mask ID.

        Returns:
            An :class:`AgentConfig` populated with the values from the retrieved
            blueprint.

        Raises:
            ValueError: If no blueprint is found for the given parameters.
        """
        from opik.api_objects import opik_client  # avoid circular import

        client = opik_client.get_client_cached()
        resolved_project_name = project_name or client._project_name
        config_client = ConfigClient(client.rest_client)

        config_data = config_client.get_blueprint(
            project_name=resolved_project_name,
            env=env,
            mask_id=mask_id,
        )
        return cls.from_backend_data(config_data)
