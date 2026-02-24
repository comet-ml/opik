import dataclasses
import logging
import typing

from opik import context_storage
from . import type_helpers
from .cache import SharedConfigCache, get_shared_cache
from .client import ConfigClient, ConfigData
from .context import get_active_config_mask

logger = logging.getLogger(__name__)


def config_decorator(
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
                f"@opik.config can only be applied to dataclasses, got {cls.__name__}"
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

            try:
                from opik.api_objects import opik_client

                client = opik_client.get_client_cached()
                resolved_project = project or client._project_name
            except Exception:
                resolved_project = project or "default"

            shared_cache = get_shared_cache(resolved_project, env, mask_id)
            shared_cache.register_fields(prefixed_field_types)

            object.__setattr__(self, "__opik_shared_cache__", shared_cache)
            object.__setattr__(self, "__opik_class_prefix__", class_prefix)
            object.__setattr__(self, "__opik_local_fields__", local_field_names)
            object.__setattr__(self, "__opik_field_types__", prefixed_field_types)
            object.__setattr__(self, "__opik_mask_id__", mask_id)
            object.__setattr__(self, "__opik_env__", env)
            object.__setattr__(self, "__opik_project_name__", resolved_project)
            object.__setattr__(self, "__opik_description__", description)
            _sync_config_with_backend(self)

        cls.__init__ = new_init  # type: ignore[assignment]

        original_getattribute = cls.__getattribute__

        def new_getattribute(self: typing.Any, attr: str) -> typing.Any:
            if attr.startswith("_") or attr not in local_field_names:
                return original_getattribute(self, attr)

            _maybe_refetch(self)
            _maybe_sync_from_cache(self, attr)
            _maybe_inject_span_metadata(self, attr)

            return original_getattribute(self, attr)

        cls.__getattribute__ = new_getattribute  # type: ignore[assignment]

        return cls

    if cls is None:
        return wrap
    return wrap(cls)


def _maybe_sync_from_cache(instance: typing.Any, attr: str) -> None:
    shared_cache: SharedConfigCache = object.__getattribute__(
        instance, "__opik_shared_cache__"
    )
    class_prefix: str = object.__getattribute__(instance, "__opik_class_prefix__")
    prefixed_key = f"{class_prefix}.{attr}"

    if prefixed_key in shared_cache.values:
        object.__setattr__(instance, attr, shared_cache.values[prefixed_key])


def _sync_config_with_backend(instance: typing.Any) -> None:
    try:
        from opik.api_objects import opik_client

        client = opik_client.get_client_cached()
        config_client = ConfigClient(client.rest_client)

        shared_cache: SharedConfigCache = object.__getattribute__(
            instance, "__opik_shared_cache__"
        )
        all_field_types = shared_cache.all_field_types
        project_name = object.__getattribute__(instance, "__opik_project_name__")
        description = object.__getattribute__(instance, "__opik_description__")
        mask_id_val = object.__getattribute__(instance, "__opik_mask_id__")
        env_val = object.__getattribute__(instance, "__opik_env__")

        existing = config_client.try_get_blueprint(
            project_name=project_name,
            env=env_val,
            mask_id=mask_id_val,
            field_types=all_field_types,
        )

        if existing is None:
            _handle_no_blueprint(
                instance,
                config_client,
                project_name,
                description,
                env_val,
                mask_id_val,
            )
        else:
            _handle_existing_blueprint(
                instance,
                config_client,
                existing,
                project_name,
                description,
            )

    except Exception:
        logger.debug("Failed to sync config with backend", exc_info=True)


def _handle_no_blueprint(
    instance: typing.Any,
    config_client: ConfigClient,
    project_name: str,
    description: typing.Optional[str],
    env_val: typing.Optional[str],
    mask_id_val: typing.Optional[str],
) -> None:
    instance_field_types: typing.Dict[str, typing.Any] = object.__getattribute__(
        instance, "__opik_field_types__"
    )
    class_prefix: str = object.__getattribute__(instance, "__opik_class_prefix__")
    prefix = f"{class_prefix}."

    fields_with_values: typing.Dict[str, typing.Tuple[typing.Any, typing.Any]] = {}
    for prefixed_name, f_type in instance_field_types.items():
        local_name = prefixed_name[len(prefix) :]
        value = object.__getattribute__(instance, local_name)
        fields_with_values[prefixed_name] = (f_type, value)

    config_client.create_blueprint_only(
        fields_with_values=fields_with_values,
        project_name=project_name,
        description=description,
    )

    shared_cache: SharedConfigCache = object.__getattribute__(
        instance, "__opik_shared_cache__"
    )
    created = config_client.try_get_blueprint(
        project_name=project_name,
        env=env_val,
        mask_id=mask_id_val,
        field_types=shared_cache.all_field_types,
    )
    if created is not None:
        _apply_backend_values(instance, created)


def _handle_existing_blueprint(
    instance: typing.Any,
    config_client: ConfigClient,
    existing: ConfigData,
    project_name: str,
    description: typing.Optional[str],
) -> None:
    instance_field_types: typing.Dict[str, typing.Any] = object.__getattribute__(
        instance, "__opik_field_types__"
    )
    class_prefix: str = object.__getattribute__(instance, "__opik_class_prefix__")
    prefix = f"{class_prefix}."

    extra_keys = set(instance_field_types) - set(existing.values)
    if extra_keys:
        extra_fields: typing.Dict[str, typing.Tuple[typing.Any, typing.Any]] = {}
        for prefixed_name in extra_keys:
            local_name = prefixed_name[len(prefix) :]
            f_type = instance_field_types[prefixed_name]
            value = object.__getattribute__(instance, local_name)
            extra_fields[prefixed_name] = (f_type, value)

        config_client.create_blueprint_only(
            fields_with_values=extra_fields,
            project_name=project_name,
            description=description,
        )

    merged_values = dict(existing.values)
    for prefixed_name in extra_keys:
        local_name = prefixed_name[len(prefix) :]
        merged_values[prefixed_name] = object.__getattribute__(instance, local_name)

    merged_data = ConfigData(
        blueprint_id=existing.blueprint_id,
        values=merged_values,
        description=existing.description,
    )
    _apply_backend_values(instance, merged_data)


def _apply_backend_values(instance: typing.Any, config_data: ConfigData) -> None:
    shared_cache: SharedConfigCache = object.__getattribute__(
        instance, "__opik_shared_cache__"
    )
    shared_cache.apply(config_data)

    class_prefix: str = object.__getattribute__(instance, "__opik_class_prefix__")
    local_fields: typing.Set[str] = object.__getattribute__(
        instance, "__opik_local_fields__"
    )
    prefix = f"{class_prefix}."

    for key, value in shared_cache.values.items():
        if key.startswith(prefix):
            local_name = key[len(prefix) :]
            if local_name in local_fields:
                object.__setattr__(instance, local_name, value)


def _maybe_refetch(instance: typing.Any) -> None:
    context_mask = get_active_config_mask()
    instance_mask = object.__getattribute__(instance, "__opik_mask_id__")

    if context_mask is not None:
        _refetch_with_mask(instance, context_mask)
        return

    if instance_mask is not None:
        return

    shared_cache: SharedConfigCache = object.__getattribute__(
        instance, "__opik_shared_cache__"
    )
    if not shared_cache.is_stale():
        return

    try:
        from opik.api_objects import opik_client

        client = opik_client.get_client_cached()
        config_client = ConfigClient(client.rest_client)
        all_field_types = shared_cache.all_field_types
        project_name = object.__getattribute__(instance, "__opik_project_name__")
        env_val = object.__getattribute__(instance, "__opik_env__")

        config_data = config_client.get_blueprint(
            project_name=project_name,
            env=env_val,
            field_types=all_field_types,
        )
        _apply_backend_values(instance, config_data)
    except Exception:
        logger.debug("Failed to refetch config", exc_info=True)


def _refetch_with_mask(instance: typing.Any, mask_id: str) -> None:
    try:
        from opik.api_objects import opik_client

        client = opik_client.get_client_cached()
        config_client = ConfigClient(client.rest_client)
        shared_cache: SharedConfigCache = object.__getattribute__(
            instance, "__opik_shared_cache__"
        )
        all_field_types = shared_cache.all_field_types
        project_name = object.__getattribute__(instance, "__opik_project_name__")

        config_data = config_client.get_blueprint(
            project_name=project_name,
            mask_id=mask_id,
            field_types=all_field_types,
        )
        _apply_backend_values(instance, config_data)
    except Exception:
        logger.debug("Failed to refetch config with mask", exc_info=True)


def _maybe_inject_span_metadata(instance: typing.Any, attr: str) -> None:
    try:
        span_data = context_storage.top_span_data()
        if span_data is None:
            return

        shared_cache: SharedConfigCache = object.__getattribute__(
            instance, "__opik_shared_cache__"
        )
        class_prefix: str = object.__getattribute__(instance, "__opik_class_prefix__")
        prefixed_key = f"{class_prefix}.{attr}"

        config_metadata = {
            "configuration": {
                "blueprint_id": shared_cache.blueprint_id,
                "values": {prefixed_key: shared_cache.values[prefixed_key]}
                if prefixed_key in shared_cache.values
                else {},
            }
        }

        span_data.update(metadata=config_metadata)
    except Exception:
        logger.debug("Failed to inject config metadata into span", exc_info=True)
