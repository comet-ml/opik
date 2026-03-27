import dataclasses
import logging
import typing

from opik.exceptions import AgentConfigNotFound
from opik.rest_api import core as rest_api_core
from . import type_helpers
from . import cache as cache_mod
from .context import get_active_config_mask

logger = logging.getLogger(__name__)

_MISSING = object()

T = typing.TypeVar("T", bound="AgentConfig")


class ConfigField(typing.NamedTuple):
    prefixed_key: str
    py_type: typing.Any
    description: typing.Optional[str]


@dataclasses.dataclass
class _OpikState:
    project: typing.Optional[str] = None
    env: typing.Optional[str] = None
    mask_id: typing.Optional[str] = None
    version: typing.Optional[str] = None
    manager: typing.Any = None
    blueprint_id: typing.Optional[str] = None
    blueprint_version: typing.Optional[str] = None
    envs: typing.Optional[typing.List[str]] = None
    is_fallback: bool = True


def _build_field_info(
    config_field: ConfigField, value: typing.Any
) -> typing.Dict[str, typing.Any]:
    info: typing.Dict[str, typing.Any] = {
        "value": type_helpers.python_value_to_metadata_value(
            value, config_field.py_type
        ),
        "type": type_helpers.python_type_to_backend_type(config_field.py_type),
    }
    if config_field.description is not None:
        info["description"] = config_field.description
    return info


class AgentConfig:
    """Base class for user-defined agent configurations.

    Subclass this and declare typed fields::

        class MyConfig(opik.AgentConfig):
            temperature: Annotated[float, "Sampling temperature"]
            model: str

    Publish a version via :meth:`opik.Opik.create_agent_config_version`::

        cfg = MyConfig(temperature=0.7, model="gpt-4")
        client.create_agent_config_version(cfg, project_name="my-project")

    Retrieve it via :meth:`opik.Opik.get_agent_config`::

        result = client.get_agent_config(
            fallback=MyConfig(temperature=0.0, model="fallback"),
            project_name="my-project",
            latest=True,
        )
    """

    __field_metadata__: typing.ClassVar[typing.Dict[str, ConfigField]]

    _opik_state: _OpikState

    def __init_subclass__(cls, **kwargs: typing.Any) -> None:
        super().__init_subclass__(**kwargs)

        if not dataclasses.is_dataclass(cls):
            dataclasses.dataclass(cls)

        for f in dataclasses.fields(cls):  # type: ignore[arg-type]
            if (
                f.default is not dataclasses.MISSING
                or f.default_factory is not dataclasses.MISSING
            ):
                raise TypeError(
                    f"Opik AgentConfig does not support default values. "
                    f"Remove the default from field '{f.name}' in {cls.__name__}."
                )

        class_prefix = cls.__name__
        fields: typing.Dict[str, ConfigField] = {}
        for f_name, f_type, desc in type_helpers.extract_dataclass_fields(cls):
            fields[f_name] = ConfigField(
                prefixed_key=f"{class_prefix}.{f_name}",
                py_type=f_type,
                description=desc,
            )
        cls.__field_metadata__ = fields

    def __post_init__(self) -> None:
        object.__setattr__(self, "_opik_state", _OpikState())

    @property
    def _state(self) -> _OpikState:
        return object.__getattribute__(self, "_opik_state")

    @property
    def envs(self) -> typing.Optional[typing.List[str]]:
        """Environment tags associated with the resolved blueprint."""
        return self._state.envs

    @property
    def is_fallback(self) -> bool:
        """True if local fallback values are used because there was an issue communicating with the backend."""
        return self._state.is_fallback

    def __getattribute__(self, attr: str) -> typing.Any:
        if attr not in type(self).__field_metadata__:
            return object.__getattribute__(self, attr)
        if self._state.project is None:
            return object.__getattribute__(self, attr)
        return self._resolve_field(attr)

    def _resolve_field(self, attr: str) -> typing.Any:
        state = self._state
        project = typing.cast(str, state.project)  # guarded by __getattribute__
        instance_cache = cache_mod.get_cached_config(
            project, state.env, state.mask_id, state.version
        )
        state.blueprint_id = instance_cache.blueprint_id
        state.blueprint_version = instance_cache.blueprint_version
        state.is_fallback = instance_cache.blueprint_id is None
        prefixed_key = type(self).__field_metadata__[attr].prefixed_key
        value = instance_cache.values.get(prefixed_key, _MISSING)
        self._inject_trace_metadata(attr, value=value)
        return value if value is not _MISSING else object.__getattribute__(self, attr)

    def _extract_fields_with_values(self) -> typing.Dict[str, tuple]:
        result: typing.Dict[str, tuple] = {}
        for f_name, cf in type(self).__field_metadata__.items():
            value = object.__getattribute__(self, f_name)
            result[cf.prefixed_key] = (cf.py_type, value, cf.description)
        return result

    @classmethod
    def _prefixed_field_types(cls) -> typing.Dict[str, typing.Any]:
        return {cf.prefixed_key: cf.py_type for cf in cls.__field_metadata__.values()}

    def _matches_blueprint(
        self,
        blueprint: typing.Any,
        fields_with_values: typing.Dict[str, tuple],
    ) -> bool:
        # Only consider blueprint keys that belong to this config class (same prefix).
        # The blueprint may contain keys from other config classes in the same project.
        class_prefix = type(self).__name__ + "."
        bp_keys = {k for k in blueprint.keys() if k.startswith(class_prefix)}
        local_keys = set(fields_with_values.keys())
        # A locally removed field does not trigger a new version — only check that
        # every local key exists in the blueprint (not the reverse).
        missing_locally = local_keys - bp_keys
        if missing_locally:
            return False

        for key, (py_type, value, desc) in fields_with_values.items():
            bp_value = blueprint.get(key)
            local_ser = type_helpers.python_value_to_backend_value(value, py_type)
            bp_ser = type_helpers.python_value_to_backend_value(bp_value, py_type)
            if local_ser != bp_ser:
                return False
            if desc != blueprint.get_field_description(key):
                return False
        return True

    def _create_version(
        self,
        manager: typing.Any,
        description: typing.Optional[str],
    ) -> str:
        fields_with_values = self._extract_fields_with_values()
        field_types = self._prefixed_field_types()

        latest = manager.get_blueprint(field_types=field_types)

        if latest is not None and self._matches_blueprint(latest, fields_with_values):
            bp = latest
        elif latest is not None:
            # There's another blueprint and the values don't match
            bp = manager.update_blueprint(
                fields_with_values=fields_with_values,
                description=description,
                field_types=field_types,
            )
        else:
            try:
                bp = manager.create_blueprint(
                    fields_with_values=fields_with_values,
                    description=description,
                    field_types=field_types,
                )
            except rest_api_core.ApiError as e:
                if e.status_code != 409:
                    raise
                # A parallel caller created the config first — re-fetch and compare.
                latest = manager.get_blueprint(field_types=field_types)
                if latest is not None and self._matches_blueprint(
                    latest, fields_with_values
                ):
                    bp = latest
                else:
                    bp = manager.update_blueprint(
                        fields_with_values=fields_with_values,
                        description=description,
                        field_types=field_types,
                    )

        self._state.manager = manager
        self._state.blueprint_id = bp.id
        self._state.blueprint_version = bp.name
        self._state.envs = bp.envs
        self._state.is_fallback = False
        return bp.name or ""

    def deploy_to(self, env: str) -> None:
        """Tag the current version with an environment name.

        Can be called after ``create_agent_config_version`` or
        ``get_agent_config``.

        Args:
            env: Environment name (e.g. ``"prod"``).
        """
        state = self._state
        if state.manager is None or state.blueprint_id is None:
            raise RuntimeError(
                "deploy_to() requires a prior call to "
                "create_agent_config_version() or get_agent_config()."
            )
        state.manager.tag_blueprint_with_env(env=env, blueprint_id=state.blueprint_id)

    @classmethod
    def _resolve_from_backend(
        cls: typing.Type[T],
        fallback: T,
        manager: typing.Any,
        project_name: str,
        *,
        env: typing.Optional[str],
        latest: bool,
        version: typing.Optional[str],
        timeout_in_seconds: typing.Optional[int] = None,
    ) -> T:
        from opik import opik_context  # avoid circular import

        if (
            opik_context.get_current_trace_data() is None
            and opik_context.get_current_span_data() is None
        ):
            raise RuntimeError(
                "get_agent_config() must be called inside a function decorated with "
                "@opik.track. Call create_agent_config_version() or get_agent_config() "
                "from within a @opik.track-decorated function."
            )

        field_types = cls._prefixed_field_types()
        mask_id = get_active_config_mask()
        resolved_env = None if (latest or version is not None) else env

        try:
            if version is not None:
                bp = manager.get_blueprint(
                    name=version,
                    mask_id=mask_id,
                    field_types=field_types,
                    timeout_in_seconds=timeout_in_seconds,
                )
                if bp is None:
                    raise AgentConfigNotFound(
                        f"No agent config blueprint found for version={version!r} in project {project_name!r}."
                    )
            elif latest:
                bp = manager.get_blueprint(
                    mask_id=mask_id,
                    field_types=field_types,
                    timeout_in_seconds=timeout_in_seconds,
                )
                if bp is None:
                    raise AgentConfigNotFound(
                        f"No agent config blueprint found in project {project_name!r}. "
                        f"Use create_agent_config_version() to publish one."
                    )
            else:
                bp = manager.get_blueprint(
                    env=env,
                    mask_id=mask_id,
                    field_types=field_types,
                    timeout_in_seconds=timeout_in_seconds,
                )
                if bp is None:
                    raise AgentConfigNotFound(
                        f"No agent config blueprint found for env={env!r} in project {project_name!r}. "
                        f"Use create_agent_config_version() and deploy_to({env!r}) to publish one."
                    )
        except AgentConfigNotFound:
            raise
        except Exception:
            logger.debug(
                "Failed to fetch agent config from backend, using fallback",
                exc_info=True,
            )
            cache_mod.init_cache_entry(
                project_name,
                resolved_env,
                mask_id,
                field_types,
                manager,
                version=version,
            )
            return fallback

        kwargs: typing.Dict[str, typing.Any] = {}
        missing_keys = [
            cf.prefixed_key
            for cf in cls.__field_metadata__.values()
            if cf.prefixed_key not in bp.keys()
        ]
        if missing_keys:
            version_label = bp.name or bp.id or "unknown"
            raise KeyError(
                f"Agent config version {version_label!r} is missing expected field(s): "
                f"{missing_keys}. The retrieved version does not contain all fields "
                f"declared in {cls.__name__}. Publish a new config or "
                f"use an existing one that includes the missing fields."
            )
        for f_name, cf in cls.__field_metadata__.items():
            kwargs[f_name] = bp[cf.prefixed_key]

        instance = cls(**kwargs)

        state = instance._state
        state.project = project_name
        state.env = resolved_env
        state.mask_id = mask_id
        state.version = version
        state.manager = manager
        state.blueprint_id = bp.id
        state.blueprint_version = bp.name
        state.envs = bp.envs
        state.is_fallback = False

        cache_mod.init_cache_entry(
            project_name,
            resolved_env,
            mask_id,
            field_types,
            manager,
            blueprint=bp,
            version=version,
        )

        return instance

    def _inject_trace_metadata(self, attr: str, value: typing.Any = _MISSING) -> None:
        from opik import exceptions, opik_context

        try:
            metadata = self._build_trace_metadata(attr, value)
            payload = {"agent_configuration": metadata}
            opik_context.update_current_trace(metadata=payload)
            opik_context.update_current_span(metadata=payload)
        except exceptions.OpikException:
            pass
        except Exception:
            logger.debug("Failed to inject config metadata into trace", exc_info=True)

    def _build_trace_metadata(
        self,
        attr: str,
        value: typing.Any,
    ) -> typing.Dict[str, typing.Any]:
        state = self._state
        config_field = type(self).__field_metadata__[attr]
        values = (
            {config_field.prefixed_key: _build_field_info(config_field, value)}
            if value is not _MISSING
            else {}
        )
        result: typing.Dict[str, typing.Any] = {
            "_blueprint_id": state.blueprint_id,
            "blueprint_version": state.blueprint_version,
        }
        if state.mask_id is not None:
            result["_mask_id"] = state.mask_id
        result["values"] = values
        return result
