import dataclasses
import logging
import typing

from opik import context_storage
from . import type_helpers
from .blueprint import Blueprint
from .cache import SharedConfigCache, get_shared_cache, _ensure_refresh_thread_started
from .client import ConfigClient
from .config import AgentConfig
from .context import get_active_config_mask

logger = logging.getLogger(__name__)

_ATTR_SHARED_CACHE = "__opik_shared_cache__"
_ATTR_CLASS_PREFIX = "__opik_class_prefix__"
_ATTR_LOCAL_FIELDS = "__opik_local_fields__"
_ATTR_FIELD_TYPES = "__opik_field_types__"
_ATTR_MASK_ID = "__opik_mask_id__"
_ATTR_ENV = "__opik_env__"
_ATTR_DESCRIPTION = "__opik_description__"
_ATTR_AGENT_CONFIG = "__opik_agent_config__"


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
    def wrap(cls: type) -> type:
        if not dataclasses.is_dataclass(cls):
            raise TypeError(
                f"@opik.agent_config can only be applied to dataclasses, got {cls.__name__}"
            )

        supported_fields = type_helpers.extract_dataclass_fields(cls)
        class_prefix = name or cls.__name__

        prefixed_field_types: typing.Dict[str, typing.Any] = {
            f"{class_prefix}.{f_name}": f_type for f_name, f_type, _ in supported_fields
        }
        local_field_names: typing.Set[str] = {
            f_name for f_name, _, _ in supported_fields
        }

        original_init = cls.__init__

        def new_init(self: typing.Any, *args: typing.Any, **kwargs: typing.Any) -> None:
            original_init(self, *args, **kwargs)

            resolved_project = resolve_project(project)
            agent_cfg = build_agent_config(resolved_project)
            shared_cache = init_shared_cache(
                resolved_project,
                env,
                mask_id,
                prefixed_field_types,
                agent_cfg=agent_cfg,
            )

            object.__setattr__(self, _ATTR_SHARED_CACHE, shared_cache)
            object.__setattr__(self, _ATTR_CLASS_PREFIX, class_prefix)
            object.__setattr__(self, _ATTR_LOCAL_FIELDS, local_field_names)
            object.__setattr__(self, _ATTR_FIELD_TYPES, prefixed_field_types)
            object.__setattr__(self, _ATTR_MASK_ID, mask_id)
            object.__setattr__(self, _ATTR_ENV, env)
            object.__setattr__(self, _ATTR_DESCRIPTION, description)
            object.__setattr__(self, _ATTR_AGENT_CONFIG, agent_cfg)
            sync_config_with_backend(self)

        cls.__init__ = new_init  # type: ignore[assignment]

        original_getattribute = cls.__getattribute__

        def new_getattribute(self: typing.Any, attr: str) -> typing.Any:
            if attr.startswith("_") or attr not in local_field_names:
                return original_getattribute(self, attr)

            refetch_if_mask_applied(self)
            maybe_sync_from_cache(self, attr)
            inject_trace_metadata(self, attr)

            return original_getattribute(self, attr)

        cls.__getattribute__ = new_getattribute  # type: ignore[assignment]

        return cls

    if cls is None:
        return wrap
    return wrap(cls)


def resolve_project(project: typing.Optional[str]) -> str:
    try:
        from opik.api_objects import opik_client

        client = opik_client.get_client_cached()
        return project or client._project_name
    except Exception:
        return project or "default"


def init_shared_cache(
    resolved_project: str,
    env: typing.Optional[str],
    mask_id: typing.Optional[str],
    prefixed_field_types: typing.Dict[str, typing.Any],
    agent_cfg: typing.Optional[AgentConfig] = None,
) -> SharedConfigCache:
    shared_cache = get_shared_cache(resolved_project, env, mask_id)
    shared_cache.register_fields(prefixed_field_types)

    if agent_cfg is not None and mask_id is None:

        def _refresh() -> typing.Optional["Blueprint"]:
            return agent_cfg.get_blueprint(
                env=env,
                field_types=shared_cache.all_field_types,
            )

        shared_cache.set_refresh_callback(_refresh)
        _ensure_refresh_thread_started()

    return shared_cache


def build_agent_config(resolved_project: str) -> typing.Optional[AgentConfig]:
    try:
        from opik.api_objects import opik_client as opik_client_module

        client = opik_client_module.get_client_cached()
        config_client = ConfigClient(client.rest_client)
        return AgentConfig(
            project_name=resolved_project,
            config_client=config_client,
            rest_client_=client.rest_client,
        )
    except Exception:
        return None


def maybe_sync_from_cache(instance: typing.Any, attr: str) -> None:
    shared_cache: SharedConfigCache = object.__getattribute__(
        instance, _ATTR_SHARED_CACHE
    )
    class_prefix: str = object.__getattribute__(instance, _ATTR_CLASS_PREFIX)
    prefixed_key = f"{class_prefix}.{attr}"

    if prefixed_key in shared_cache.values:
        object.__setattr__(instance, attr, shared_cache.values[prefixed_key])


def sync_config_with_backend(instance: typing.Any) -> None:
    try:
        agent_cfg: typing.Optional[AgentConfig] = object.__getattribute__(
            instance, _ATTR_AGENT_CONFIG
        )
        if agent_cfg is None:
            return

        shared_cache: SharedConfigCache = object.__getattribute__(
            instance, _ATTR_SHARED_CACHE
        )
        all_field_types = shared_cache.all_field_types
        description = object.__getattribute__(instance, _ATTR_DESCRIPTION)
        mask_id_val = object.__getattribute__(instance, _ATTR_MASK_ID)
        env_val = object.__getattribute__(instance, _ATTR_ENV)
        instance_field_types: typing.Dict[str, typing.Any] = object.__getattribute__(
            instance, _ATTR_FIELD_TYPES
        )

        existing = agent_cfg.get_blueprint(
            env=env_val,
            mask_id=mask_id_val,
            field_types=all_field_types,
        )

        pinned = mask_id_val is not None or env_val is not None

        if existing is None:
            bp = create_and_apply(
                instance,
                agent_cfg,
                instance_field_types.keys(),
                description,
                shared_cache,
            )
            if env_val is not None and bp.id is not None:
                agent_cfg.tag_bluepring_with_env(env=env_val, blueprint_id=bp.id)

        elif pinned:
            apply_backend_values(instance, existing)

        else:
            extra_keys = set(instance_field_types) - set(existing.values)
            if extra_keys:
                create_and_apply(
                    instance,
                    agent_cfg,
                    extra_keys,
                    description,
                    shared_cache,
                )
            else:
                apply_backend_values(instance, existing)

    except Exception:
        logger.debug("Failed to sync config with backend", exc_info=True)


def create_and_apply(
    instance: typing.Any,
    agent_cfg: AgentConfig,
    prefixed_keys: typing.Iterable[str],
    description: typing.Optional[str],
    shared_cache: SharedConfigCache,
) -> Blueprint:
    instance_field_types: typing.Dict[str, typing.Any] = object.__getattribute__(
        instance, _ATTR_FIELD_TYPES
    )
    class_prefix: str = object.__getattribute__(instance, _ATTR_CLASS_PREFIX)
    prefix = f"{class_prefix}."

    fields_with_values: typing.Dict[str, typing.Tuple[typing.Any, typing.Any]] = {}
    for prefixed_name in prefixed_keys:
        local_name = prefixed_name[len(prefix) :]
        f_type = instance_field_types[prefixed_name]
        value = object.__getattribute__(instance, local_name)
        fields_with_values[prefixed_name] = (f_type, value)

    bp = agent_cfg.create_blueprint(
        fields_with_values=fields_with_values,
        description=description,
        field_types=shared_cache.all_field_types,
    )
    apply_backend_values(instance, bp)
    return bp


def apply_backend_values(instance: typing.Any, blueprint: Blueprint) -> None:
    shared_cache: SharedConfigCache = object.__getattribute__(
        instance, _ATTR_SHARED_CACHE
    )
    shared_cache.apply(blueprint)

    class_prefix: str = object.__getattribute__(instance, _ATTR_CLASS_PREFIX)
    local_fields: typing.Set[str] = object.__getattribute__(
        instance, _ATTR_LOCAL_FIELDS
    )
    prefix = f"{class_prefix}."

    for key, value in shared_cache.values.items():
        if key.startswith(prefix):
            local_name = key[len(prefix) :]
            if local_name in local_fields:
                object.__setattr__(instance, local_name, value)


def refetch_if_mask_applied(instance: typing.Any) -> None:
    context_mask = get_active_config_mask()
    if context_mask is None:
        return

    try:
        agent_cfg: typing.Optional[AgentConfig] = object.__getattribute__(
            instance, _ATTR_AGENT_CONFIG
        )
        if agent_cfg is None:
            return

        shared_cache: SharedConfigCache = object.__getattribute__(
            instance, _ATTR_SHARED_CACHE
        )
        bp = agent_cfg.get_blueprint(
            mask_id=context_mask,
            field_types=shared_cache.all_field_types,
        )
        if bp is not None:
            apply_backend_values(instance, bp)
    except Exception:
        logger.debug("Failed to refetch config", exc_info=True)


def inject_trace_metadata(instance: typing.Any, attr: str) -> None:
    try:
        trace_data = context_storage.get_trace_data()
        if trace_data is None:
            return

        shared_cache: SharedConfigCache = object.__getattribute__(
            instance, _ATTR_SHARED_CACHE
        )
        class_prefix: str = object.__getattribute__(instance, _ATTR_CLASS_PREFIX)
        prefixed_key = f"{class_prefix}.{attr}"

        config_metadata = {
            "agent_configuration": {
                "blueprint_id": shared_cache.blueprint_id,
                "values": {prefixed_key: shared_cache.values[prefixed_key]}
                if prefixed_key in shared_cache.values
                else {},
            }
        }

        trace_data.update(metadata=config_metadata)
    except Exception:
        logger.debug("Failed to inject config metadata into trace", exc_info=True)
