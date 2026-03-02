import dataclasses
import logging
import typing

from opik import exceptions, opik_context
from opik.api_objects import opik_client
from . import type_helpers
from . import blueprint
from . import cache
from . import config
from .context import get_active_config_mask

logger = logging.getLogger(__name__)

_MISSING = object()


# Extra keys injected by our decorator
class AgentConfigInstance(typing.Protocol):
    __opik_field_map__: typing.Dict[str, str]  # local_name → prefixed_key
    __opik_field_types__: typing.Dict[str, typing.Any]  # prefixed_key → type
    __opik_mask_id__: typing.Optional[str]
    __opik_env__: typing.Optional[str]
    __opik_description__: typing.Optional[str]
    __opik_agent_config__: typing.Optional[config.AgentConfig]
    __opik_project__: str


# Attribute name references derived from the protocol — avoids magic strings at call sites.
# e.g. _F.__opik_field_map__ == "__opik_field_map__"
_F = type(
    "_F",
    (),
    {
        k: k
        for k in typing.get_type_hints(AgentConfigInstance, localns={"config": config})
    },
)()


def agent_config_decorator(
    cls: typing.Optional[type] = None,
    *,
    name: typing.Optional[str] = None,
    project: typing.Optional[str] = None,
    workspace: typing.Optional[str] = None,
    env: typing.Optional[str] = None,
    mask_id: typing.Optional[str] = None,
    description: typing.Optional[str] = None,
) -> typing.Any:
    """Decorate a dataclass to back its fields with an Opik agent config blueprint.

    Dataclass fields become live config values: on attribute access the
    decorator transparently returns the value from the cached remote blueprint
    instead of the locally-set default.

    Can be used with or without arguments::

        @agent_config_decorator
        class MyConfig:
            model: str = "gpt-4o"

        @agent_config_decorator(env="production", description="Prod config")
        class MyConfig:
            model: str = "gpt-4o"

    Args:
        cls: The class being decorated (set automatically).
        name: Prefix used for backend field keys. Defaults to the class name.
        project: Opik project name. Defaults to the client's default project.
        workspace: Opik workspace. Reserved for future use.
        env: Pin this config instance to a specific environment blueprint.
        mask_id: ID of a mask blueprint to overlay on every attribute read.
        description: Description stored when a new blueprint is auto-created.
    """

    def wrap(cls: type) -> type:
        if not dataclasses.is_dataclass(cls):
            cls = dataclasses.dataclass(cls)

        supported_fields = type_helpers.extract_dataclass_fields(cls)
        class_prefix = name or cls.__name__

        prefixed_field_types: typing.Dict[str, typing.Any] = {
            f"{class_prefix}.{f_name}": f_type for f_name, f_type, _ in supported_fields
        }
        field_map: typing.Dict[str, str] = {
            f_name: f"{class_prefix}.{f_name}" for f_name, _, _ in supported_fields
        }

        original_init = cls.__init__

        def new_init(self: typing.Any, *args: typing.Any, **kwargs: typing.Any) -> None:
            original_init(self, *args, **kwargs)

            client = opik_client.get_client_cached()

            resolved_project = project or client.project_name
            agent_config = config.AgentConfig(
                project_name=resolved_project,
                rest_client_=client.rest_client,
            )
            _init_cache_entry(
                resolved_project,
                env,
                mask_id,
                prefixed_field_types,
                agent_config=agent_config,
            )

            object.__setattr__(self, _F.__opik_field_map__, field_map)
            object.__setattr__(self, _F.__opik_field_types__, prefixed_field_types)
            object.__setattr__(self, _F.__opik_mask_id__, mask_id)
            object.__setattr__(self, _F.__opik_env__, env)
            object.__setattr__(self, _F.__opik_description__, description)
            object.__setattr__(self, _F.__opik_agent_config__, agent_config)
            object.__setattr__(self, _F.__opik_project__, resolved_project)
            _resolve_and_cache_blueprint(self)

        cls.__init__ = new_init  # type: ignore[assignment]

        original_getattribute = cls.__getattribute__

        def new_getattribute(self: typing.Any, attr: str) -> typing.Any:
            if attr.startswith("_") or attr not in field_map:
                return original_getattribute(self, attr)

            instance: AgentConfigInstance = self
            masked = _get_masked_value(instance, attr)
            if masked is not _MISSING:
                _inject_trace_metadata(instance, attr, value=masked)
                return masked

            instance_cache = get_cached_config(instance)
            prefixed_key = instance.__opik_field_map__[attr]
            value = instance_cache.values.get(prefixed_key, _MISSING)
            _inject_trace_metadata(
                instance, attr, value=value, shared_cache=instance_cache
            )
            return value if value is not _MISSING else original_getattribute(self, attr)

        cls.__getattribute__ = new_getattribute  # type: ignore[assignment]

        return cls

    if cls is None:
        return wrap
    return wrap(cls)


def get_cached_config(instance: AgentConfigInstance) -> cache.SharedConfigCache:
    """Helper method to create a key from the current instance and fetch it from the cache"""
    return cache.get_shared_cache(
        instance.__opik_project__,
        instance.__opik_env__,
        instance.__opik_mask_id__,
    )


def _init_cache_entry(
    resolved_project: str,
    env: typing.Optional[str],
    mask_id: typing.Optional[str],
    prefixed_field_types: typing.Dict[str, typing.Any],
    agent_config: typing.Optional[config.AgentConfig] = None,
) -> cache.SharedConfigCache:
    """Initialise (or return the existing) shared cache entry for a config key.

    Registers the given field types and, when ``agent_config`` is provided and no
    mask is active, attaches a background-refresh callback so the cache stays
    up to date.

    Args:
        resolved_project: Resolved Opik project name.
        env: Environment name used as part of the cache key.
        mask_id: Mask ID used as part of the cache key.
        prefixed_field_types: Mapping of prefixed field key to Python type.
        agent_config: ``AgentConfig`` instance used to build the refresh callback.
    """
    shared_cache = cache.get_shared_cache(resolved_project, env, mask_id)
    shared_cache.register_fields(prefixed_field_types)

    if agent_config is not None and mask_id is None:

        def _refresh() -> typing.Optional[blueprint.Blueprint]:
            return agent_config.get_blueprint(
                env=env,
                mask_id=mask_id,
                field_types=shared_cache.all_field_types,
            )

        shared_cache.set_refresh_callback(_refresh)
        cache._ensure_refresh_thread_started()

    return shared_cache


def _resolve_and_cache_blueprint(instance: AgentConfigInstance) -> None:
    """Fetch the remote blueprint and populate the shared cache.

    If the remote blueprint already contains all local fields, only the cache
    is updated. Otherwise a new blueprint is created with the missing fields
    merged in (unless the config is pinned to an env or mask).
    """
    agent_cfg = instance.__opik_agent_config__
    if agent_cfg is None:
        return

    shared_cache = get_cached_config(instance)
    instance_field_types = instance.__opik_field_types__

    extra_keys = set(instance_field_types.keys()) - set(shared_cache.values.keys())
    # If no difference with cache - no need to do any extra fetches
    if not extra_keys:
        return

    environment = instance.__opik_env__
    mask_id = instance.__opik_mask_id__

    existing = agent_cfg.get_blueprint(
        env=environment,
        mask_id=mask_id,
        field_types=instance_field_types,
    )

    pinned = environment is not None or mask_id is not None

    if existing:
        extra_keys = set(instance_field_types) - set(existing.values)
        # No need to update the pinned version
        if pinned or not extra_keys:
            shared_cache.update(existing)
            return

    # Create new version if no blueprint exists or keys need updating
    field_map = instance.__opik_field_map__
    local_keys = (
        field_map.keys()
        if existing is None
        else {k for k, v in field_map.items() if v in extra_keys}
    )
    new_blueprint = _create_blueprint(
        instance, agent_cfg, local_keys, instance.__opik_description__, shared_cache
    )
    # Tag new blueprint with the env
    if not existing and environment is not None and new_blueprint.id is not None:
        agent_cfg.tag_blueprint_with_env(env=environment, blueprint_id=new_blueprint.id)
    shared_cache.update(new_blueprint)


def _create_blueprint(
    instance: AgentConfigInstance,
    agent_cfg: config.AgentConfig,
    local_keys: typing.Iterable[str],
    description: typing.Optional[str],
    shared_cache: cache.SharedConfigCache,
) -> blueprint.Blueprint:
    """Create a new blueprint from the instance's current field values.

    Args:
        instance: The decorated config instance providing field values.
        agent_cfg: ``AgentConfig`` used to call the backend.
        local_keys: Subset of local (unprefixed) field names to include.
        description: Description forwarded to the backend.
        cache: Shared cache whose ``all_field_types`` is used for
            deserialisation of the returned blueprint.
    """
    fields_with_values: typing.Dict[str, typing.Tuple[typing.Any, typing.Any]] = {}
    for local_name in local_keys:
        prefixed_key = instance.__opik_field_map__[local_name]
        f_type = instance.__opik_field_types__[prefixed_key]
        value = object.__getattribute__(instance, local_name)
        fields_with_values[prefixed_key] = (f_type, value)

    bp = agent_cfg.create_blueprint(
        fields_with_values=fields_with_values,
        description=description,
        field_types=shared_cache.all_field_types,
    )
    return bp


def _get_masked_value(instance: AgentConfigInstance, attr: str) -> typing.Any:
    """Return the mask-overridden value for ``attr``, or ``_MISSING``.

    Checks the active context mask (set via ``opik_config_mask`` context var).
    Fetches and caches the mask blueprint on first access per mask ID.
    Returns ``_MISSING`` when no mask is active or the mask has no value for
    the requested field.
    """
    context_mask = get_active_config_mask()
    if context_mask is None:
        return _MISSING

    try:
        agent_cfg = instance.__opik_agent_config__
        if agent_cfg is None:
            return _MISSING

        prefixed_key = instance.__opik_field_map__[attr]

        base_cache = get_cached_config(instance)
        mask_cache = cache.get_shared_cache(
            instance.__opik_project__, instance.__opik_env__, context_mask
        )
        mask_cache.register_fields(base_cache.all_field_types)

        if not mask_cache.values:
            bp = agent_cfg.get_blueprint(
                mask_id=context_mask,
                field_types=base_cache.all_field_types,
            )
            if bp is not None:
                mask_cache.update(bp)

        if prefixed_key in mask_cache.values:
            return mask_cache.values[prefixed_key]
    except Exception:
        logger.debug("Failed to get masked config value", exc_info=True)

    return _MISSING


def _inject_trace_metadata(
    instance: AgentConfigInstance,
    attr: str,
    value: typing.Any = _MISSING,
    *,
    shared_cache: typing.Optional[cache.SharedConfigCache] = None,
) -> None:
    """Attach the accessed config value to the active trace's metadata.

    No-ops silently when there is no active trace or on any error.

    Args:
        instance: The decorated config instance.
        attr: Local (unprefixed) field name being accessed.
        value: The resolved value to record. Falls back to the cached value
            when ``_MISSING``.
    """
    try:
        resolved_cache = (
            shared_cache if shared_cache is not None else get_cached_config(instance)
        )
        prefixed_key = instance.__opik_field_map__[attr]

        if value is not _MISSING:
            values = {prefixed_key: value}
        elif prefixed_key in resolved_cache.values:
            values = {prefixed_key: resolved_cache.values[prefixed_key]}
        else:
            values = {}

        config_metadata = {
            "agent_configuration": {
                "blueprint_id": resolved_cache.blueprint_id,
                "values": values,
            }
        }

        opik_context.update_current_trace(metadata=config_metadata)
    except exceptions.OpikException:
        # Happens when there's no trace in the context
        pass
    except Exception:
        logger.debug("Failed to inject config metadata into trace", exc_info=True)
