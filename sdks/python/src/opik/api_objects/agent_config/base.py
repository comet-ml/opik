import dataclasses
import logging
import typing

from opik.exceptions import ConfigMismatch, ConfigNotFound
from opik.rest_api import core as rest_api_core
from .. import type_helpers
from . import cache as cache_mod, types
from .context import get_active_config_mask, get_active_config_blueprint_name

logger = logging.getLogger(__name__)

_MISSING = object()

T = typing.TypeVar("T", bound="Config")


@dataclasses.dataclass
class _OpikState:
    project: typing.Optional[str] = None
    env: typing.Optional[str] = None
    mask_id: typing.Optional[str] = None
    version: typing.Optional[str] = None
    manager: typing.Any = None
    blueprint_id: typing.Optional[str] = None
    blueprint_version: typing.Optional[str] = None
    is_fallback: bool = True


def _infer_python_type(value: typing.Any) -> typing.Any:
    """Return the Python type for a field value. ``None`` maps to ``str``."""
    if value is None:
        return str
    return type(value)


def _require_track_context() -> None:
    """Raise RuntimeError unless called inside an @opik.track function."""
    from opik import opik_context  # avoid circular import

    if (
        opik_context.get_current_trace_data() is None
        and opik_context.get_current_span_data() is None
    ):
        raise RuntimeError(
            "get_or_create_config() must be called inside a function decorated with "
            "@opik.track. Call get_or_create_config() from within a @opik.track-decorated function."
        )


def _apply_context_overrides(
    version: typing.Optional[str],
) -> typing.Tuple[typing.Optional[str], typing.Optional[str]]:
    """Apply runner-supplied context overrides. Returns ``(version, mask_id)``."""
    blueprint_name_override = get_active_config_blueprint_name()
    if blueprint_name_override is not None:
        version = blueprint_name_override
    return version, get_active_config_mask()


def _fetch_by_selector(
    manager: typing.Any,
    *,
    version: typing.Optional[str],
    env: typing.Optional[str],
    mask_id: typing.Optional[str],
    field_types: typing.Dict[str, typing.Any],
    timeout_in_seconds: typing.Optional[int],
) -> typing.Any:
    """Fetch a blueprint by version, env, or latest (in priority order)."""
    if version is not None:
        return manager.get_blueprint(
            name=version,
            mask_id=mask_id,
            field_types=field_types,
            timeout_in_seconds=timeout_in_seconds,
        )
    if env is not None:
        return manager.get_blueprint(
            env=env,
            mask_id=mask_id,
            field_types=field_types,
            timeout_in_seconds=timeout_in_seconds,
        )
    return manager.get_blueprint(
        mask_id=mask_id,
        field_types=field_types,
        timeout_in_seconds=timeout_in_seconds,
    )


def _init_fallback_cache_entry(
    project_name: str,
    resolved_env: typing.Optional[str],
    mask_id: typing.Optional[str],
    field_types: typing.Dict[str, typing.Any],
    manager: typing.Any,
    version: typing.Optional[str],
) -> None:
    """Record a cache entry with no blueprint; subsequent reads will hit it as fallback."""
    logger.debug("Failed to fetch config from backend, using fallback", exc_info=True)
    cache_mod.init_cache_entry(
        project_name,
        resolved_env,
        mask_id,
        field_types,
        manager,
        version=version,
    )


def _validate_prompt_project_names(
    config: "Config",
    project_name: str,
) -> None:
    """Raise ConfigMismatch if any Prompt/ChatPrompt field belongs to a different project."""
    from opik.api_objects.prompt.base_prompt import BasePrompt  # avoid circular import

    mismatched = []
    for name in type(config).__field_names__:
        value = object.__getattribute__(config, name)
        if isinstance(value, BasePrompt):
            prompt_project = value.project_name
            if prompt_project is not None and prompt_project != project_name:
                mismatched.append((name, prompt_project))

    if mismatched:
        details = ", ".join(f"{name!r} (project={proj!r})" for name, proj in mismatched)
        raise ConfigMismatch(
            f"Config project is {project_name!r}, but the following prompt field(s) "
            f"belong to a different project: {details}. "
            f"All prompts referenced in a config must belong to the same project as the config."
        )


def _validate_blueprint_schema(cls: typing.Type["Config"], bp: typing.Any) -> None:
    """Raise ConfigMismatch if ``bp`` is missing any field declared on ``cls``."""
    missing_keys = [name for name in cls.__field_names__ if name not in bp.keys()]
    if missing_keys:
        version_label = bp.name or bp.id or "unknown"
        raise ConfigMismatch(
            f"Config version {version_label!r} is missing expected field(s): "
            f"{missing_keys}. The retrieved version does not contain all fields "
            f"declared in {cls.__name__}."
        )


def _build_live_instance(
    cls: typing.Type[T],
    bp: typing.Any,
    *,
    project_name: str,
    resolved_env: typing.Optional[str],
    mask_id: typing.Optional[str],
    version: typing.Optional[str],
    manager: typing.Any,
    field_types: typing.Dict[str, typing.Any],
) -> T:
    """Construct a backend-backed Config instance and seed its cache entry."""
    _validate_blueprint_schema(cls, bp)

    kwargs: typing.Dict[str, typing.Any] = {
        name: bp[name] for name in cls.__field_names__
    }
    instance = cls(**kwargs)

    state = instance._state
    state.project = project_name
    state.env = resolved_env
    state.mask_id = mask_id
    state.version = version
    state.manager = manager
    state.blueprint_id = bp.id
    state.blueprint_version = bp.name
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


def _missing_config_error(
    project_name: str,
    *,
    env: typing.Optional[str],
    version: typing.Optional[str],
) -> ConfigNotFound:
    if version is not None:
        return ConfigNotFound(
            f"No config found for version={version!r} in project {project_name!r}."
        )
    return ConfigNotFound(
        f"No config found for env={env!r} in project {project_name!r}."
    )


class Config:
    """Base class for user-defined configurations.

    Subclass this and declare the fields you want to publish. The annotations
    are used **only to register field names** (so the class can be turned into
    a dataclass); the declared types are not inspected or enforced. The actual
    field type sent to the backend is inferred at runtime from the value you
    pass — ``type(value)``, or ``str`` when the value is ``None``. Mismatches
    between the annotation and the value are therefore harmless, and using
    ``typing.Any`` is fine if you do not want to commit to a static type::

        class MyConfig(opik.Config):
            temperature: float = 0.7          # default value — used when
            model: str = "gpt-4"              #   no arg is passed
            hint: typing.Any = None           # type inferred from the value
                                              #   actually used at runtime

    Publish a version via :meth:`opik.Opik.create_config`::

        cfg = MyConfig(temperature=0.5)       # defaults fill in the rest
        client.create_config(cfg, project_name="my-project")

    Retrieve (or auto-create from fallback) via :meth:`opik.Opik.get_or_create_config`::

        result = client.get_or_create_config(
            fallback=MyConfig(),
            project_name="my-project",
        )
    """

    __field_names__: typing.ClassVar[typing.Tuple[str, ...]] = ()

    def __init_subclass__(cls, **kwargs: typing.Any) -> None:
        super().__init_subclass__(**kwargs)

        if not dataclasses.is_dataclass(cls):
            dataclasses.dataclass(cls)

        cls.__field_names__ = tuple(
            f.name
            for f in dataclasses.fields(cls)  # type: ignore[arg-type]
        )

    def __init__(self) -> None:
        # Base-class instantiation path used when ``get_or_create_config`` is
        # called without a fallback. Subclasses override this via the
        # dataclass-generated ``__init__``, which still triggers
        # ``__post_init__`` below.
        self.__post_init__()

    def __post_init__(self) -> None:
        object.__setattr__(self, "_opik_state", _OpikState())

    @property
    def _state(self) -> _OpikState:
        return object.__getattribute__(self, "_opik_state")

    @property
    def is_fallback(self) -> bool:
        """True if local fallback values are used because there was an issue communicating with the backend."""
        return self._state.is_fallback

    def _infer_field_types(self) -> typing.Dict[str, typing.Any]:
        """Return ``{field_name: python_type}`` derived from this instance's values."""
        return {
            name: _infer_python_type(object.__getattribute__(self, name))
            for name in type(self).__field_names__
        }

    def __getattribute__(self, attr: str) -> typing.Any:
        field_names = type(self).__field_names__
        if attr in field_names:
            if self._state.project is None:
                return object.__getattribute__(self, attr)
            return self._resolve_field(attr)
        # Generic ``Config`` instances (no declared schema) resolve unknown
        # attributes from the live cache so users can access backend values
        # even when ``get_or_create_config`` was called without a fallback.
        if (
            not field_names
            and not attr.startswith("_")
            and not hasattr(type(self), attr)
            and self._state.project is not None
        ):
            return self._resolve_field(attr)
        return object.__getattribute__(self, attr)

    def _resolve_field(self, attr: str) -> typing.Any:
        state = self._state
        project = typing.cast(str, state.project)
        instance_cache = cache_mod.get_cached_config(
            project, state.env, state.mask_id, state.version
        )
        state.blueprint_id = instance_cache.blueprint_id
        state.blueprint_version = instance_cache.blueprint_version
        state.is_fallback = instance_cache.blueprint_id is None
        value = instance_cache.values.get(attr, _MISSING)
        self._inject_trace_metadata(attr, value=value)
        return value if value is not _MISSING else object.__getattribute__(self, attr)

    def _extract_fields_with_values(self) -> typing.Dict[str, types.FieldValueSpec]:
        result: typing.Dict[str, types.FieldValueSpec] = {}
        for name in type(self).__field_names__:
            value = object.__getattribute__(self, name)
            result[name] = types.FieldValueSpec(
                python_type=_infer_python_type(value),
                value=value,
            )
        return result

    @classmethod
    def _get_or_create_from_backend(
        cls: typing.Type[T],
        manager: typing.Any,
        project_name: str,
        *,
        fallback: typing.Optional[T] = None,
        env: typing.Optional[str],
        version: typing.Optional[str],
        auto_create_if_empty: bool = False,
        timeout_in_seconds: typing.Optional[int] = None,
    ) -> T:
        _require_track_context()
        version, mask_id = _apply_context_overrides(version)
        # A runner context that pins a specific blueprint name is an explicit
        # version request — missing it must raise ConfigNotFound, not auto-create.
        if get_active_config_blueprint_name() is not None:
            auto_create_if_empty = False
        resolved_env = None if version is not None else env
        # Field types come from the fallback's runtime values when available;
        # without a fallback we pass an empty mapping and rely on the
        # backend-declared type for each value (see Blueprint._convert_primitives).
        field_types: typing.Dict[str, typing.Any] = (
            fallback._infer_field_types() if fallback is not None else {}
        )

        try:
            bp = _fetch_by_selector(
                manager,
                version=version,
                env=env,
                mask_id=mask_id,
                field_types=field_types,
                timeout_in_seconds=timeout_in_seconds,
            )
        except Exception:
            if fallback is None:
                raise
            _init_fallback_cache_entry(
                project_name, resolved_env, mask_id, field_types, manager, version
            )
            return fallback

        if bp is not None:
            return _build_live_instance(
                cls,
                bp,
                project_name=project_name,
                resolved_env=resolved_env,
                mask_id=mask_id,
                version=version,
                manager=manager,
                field_types=field_types,
            )

        if not auto_create_if_empty:
            raise _missing_config_error(project_name, env=env, version=version)

        # env="prod" default path: the initial fetch filtered by env, so probe
        # project-wide to distinguish "project empty" (auto-create) from
        # "prod tag missing while other configs exist" (surface ConfigNotFound).
        # The version="latest" path already queried the project-wide latest.
        if env is not None:
            try:
                probe = manager.get_blueprint(
                    field_types=field_types,
                    timeout_in_seconds=timeout_in_seconds,
                )
            except Exception:
                if fallback is None:
                    raise
                _init_fallback_cache_entry(
                    project_name, resolved_env, mask_id, field_types, manager, version
                )
                return fallback
            if probe is not None:
                raise ConfigNotFound(
                    f"No config tagged with env={env!r} in project {project_name!r}, "
                    f"but other configs exist. Tag a version with env={env!r} "
                    f"via set_config_env(), or pass an explicit env/version."
                )

        if fallback is None:
            raise ConfigNotFound(
                f"No config found in project {project_name!r}. Pass a `fallback` "
                f"to auto-create one."
            )

        return cls._create_from_fallback(
            fallback=fallback,
            manager=manager,
            project_name=project_name,
            mask_id=mask_id,
            field_types=field_types,
        )

    @classmethod
    def _create_from_fallback(
        cls: typing.Type[T],
        fallback: T,
        manager: typing.Any,
        project_name: str,
        mask_id: typing.Optional[str],
        field_types: typing.Dict[str, typing.Any],
    ) -> T:
        _validate_prompt_project_names(fallback, project_name)
        fields_with_values = fallback._extract_fields_with_values()
        try:
            bp = manager.create_blueprint(
                fields_with_values=fields_with_values,
                field_types=field_types,
            )
        except rest_api_core.ApiError as e:
            if e.status_code != 409:
                raise
            # Parallel caller created it first — fetch the current latest.
            bp = manager.get_blueprint(field_types=field_types)
            if bp is None:
                raise ConfigNotFound(
                    f"Failed to create or fetch config in project {project_name!r}."
                )

        return _build_live_instance(
            cls,
            bp,
            project_name=project_name,
            resolved_env=None,
            mask_id=mask_id,
            version=None,
            manager=manager,
            field_types=field_types,
        )

    def _create_from_instance(
        self,
        manager: typing.Any,
        description: typing.Optional[str] = None,
    ) -> str:
        _validate_prompt_project_names(self, manager.project_name)
        fields_with_values = self._extract_fields_with_values()
        field_types = self._infer_field_types()

        latest = manager.get_blueprint(field_types=field_types)

        if latest is not None:
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
                bp = manager.update_blueprint(
                    fields_with_values=fields_with_values,
                    description=description,
                    field_types=field_types,
                )

        self._state.manager = manager
        self._state.blueprint_id = bp.id
        self._state.blueprint_version = bp.name
        self._state.is_fallback = False
        return bp.name or ""

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
        if value is not _MISSING:
            py_type = _infer_python_type(value)
            values: typing.Dict[str, typing.Any] = {
                attr: {
                    "value": type_helpers.python_value_to_metadata_value(
                        value, py_type
                    ),
                    "type": type_helpers.python_type_to_backend_type(py_type),
                }
            }
        else:
            values = {}
        result: typing.Dict[str, typing.Any] = {
            "_blueprint_id": state.blueprint_id,
            "blueprint_version": state.blueprint_version,
        }
        if state.mask_id is not None:
            result["_mask_id"] = state.mask_id
        result["values"] = values
        return result
