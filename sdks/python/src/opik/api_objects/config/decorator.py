import dataclasses
import logging
import time
import typing

from opik import context_storage
from . import type_helpers
from .client import ConfigClient, ConfigData
from .context import get_active_config_mask

logger = logging.getLogger(__name__)

_OPIK_INTERNAL_ATTRS = frozenset(
    {
        "__opik_config_id__",
        "__opik_blueprint_id__",
        "__opik_mask_id__",
        "__opik_env__",
        "__opik_ttl_seconds__",
        "__opik_last_fetch__",
        "__opik_field_types__",
        "__opik_project_name__",
        "__opik_values_cache__",
        "__opik_description__",
    }
)

DEFAULT_TTL_SECONDS = 300


def config_decorator(
    cls: typing.Optional[type] = None,
    *,
    name: typing.Optional[str] = None,
    project: typing.Optional[str] = None,
    workspace: typing.Optional[str] = None,
    env: typing.Optional[str] = None,
    mask_id: typing.Optional[str] = None,
    ttl_seconds: int = DEFAULT_TTL_SECONDS,
    description: typing.Optional[str] = None,
) -> typing.Any:
    def wrap(cls: type) -> type:
        if not dataclasses.is_dataclass(cls):
            raise TypeError(
                f"@opik.config can only be applied to dataclasses, got {cls.__name__}"
            )

        supported_fields = type_helpers.extract_dataclass_fields(cls)
        field_types: typing.Dict[str, typing.Any] = {
            f_name: f_type for f_name, f_type, _ in supported_fields
        }

        original_init = cls.__init__

        def new_init(self: typing.Any, *args: typing.Any, **kwargs: typing.Any) -> None:
            original_init(self, *args, **kwargs)
            object.__setattr__(self, "__opik_config_id__", None)
            object.__setattr__(self, "__opik_blueprint_id__", None)
            object.__setattr__(self, "__opik_mask_id__", mask_id)
            object.__setattr__(self, "__opik_env__", env)
            object.__setattr__(self, "__opik_ttl_seconds__", ttl_seconds)
            object.__setattr__(self, "__opik_last_fetch__", None)
            object.__setattr__(self, "__opik_field_types__", field_types)
            object.__setattr__(self, "__opik_project_name__", project)
            object.__setattr__(self, "__opik_values_cache__", {})
            object.__setattr__(self, "__opik_description__", description)
            _sync_config_with_backend(self)

        cls.__init__ = new_init  # type: ignore[assignment]

        original_getattribute = cls.__getattribute__

        def new_getattribute(self: typing.Any, attr: str) -> typing.Any:
            if attr.startswith("_") or attr not in field_types:
                return original_getattribute(self, attr)

            _maybe_refetch(self)
            _maybe_inject_span_metadata(self)

            return original_getattribute(self, attr)

        cls.__getattribute__ = new_getattribute  # type: ignore[assignment]

        return cls

    if cls is None:
        return wrap
    return wrap(cls)


def _sync_config_with_backend(instance: typing.Any) -> None:
    try:
        from opik.api_objects import opik_client

        client = opik_client.get_client_cached()
        config_client = ConfigClient(client.rest_client)

        field_types = object.__getattribute__(instance, "__opik_field_types__")
        project_name = object.__getattribute__(instance, "__opik_project_name__")
        description = object.__getattribute__(instance, "__opik_description__")

        fields_with_values: typing.Dict[str, typing.Tuple[typing.Any, typing.Any]] = {}
        for f_name, f_type in field_types.items():
            value = object.__getattribute__(instance, f_name)
            fields_with_values[f_name] = (f_type, value)

        config_data = config_client.create_config(
            fields_with_values=fields_with_values,
            project_name=project_name,
            description=description,
        )

        _apply_backend_values(instance, config_data)

        mask_id_val = object.__getattribute__(instance, "__opik_mask_id__")
        env_val = object.__getattribute__(instance, "__opik_env__")

        if mask_id_val is not None or env_val is not None:
            pinned_data = config_client.get_blueprint(
                config_id=config_data.config_id,
                mask_id=mask_id_val,
                env=env_val,
                field_types=field_types,
            )
            _apply_backend_values(instance, pinned_data)

    except Exception:
        logger.debug("Failed to sync config with backend", exc_info=True)


def _apply_backend_values(instance: typing.Any, config_data: ConfigData) -> None:
    object.__setattr__(instance, "__opik_config_id__", config_data.config_id)
    object.__setattr__(instance, "__opik_blueprint_id__", config_data.blueprint_id)
    object.__setattr__(instance, "__opik_last_fetch__", time.monotonic())

    values_cache: typing.Dict[str, typing.Any] = {}
    field_types = object.__getattribute__(instance, "__opik_field_types__")
    for key, value in config_data.values.items():
        if key in field_types:
            object.__setattr__(instance, key, value)
            values_cache[key] = value
    object.__setattr__(instance, "__opik_values_cache__", values_cache)


def _maybe_refetch(instance: typing.Any) -> None:
    context_mask = get_active_config_mask()
    instance_mask = object.__getattribute__(instance, "__opik_mask_id__")

    if context_mask is not None:
        _refetch_with_mask(instance, context_mask)
        return

    if instance_mask is not None:
        return

    ttl = object.__getattribute__(instance, "__opik_ttl_seconds__")
    last_fetch = object.__getattribute__(instance, "__opik_last_fetch__")

    if last_fetch is not None and (time.monotonic() - last_fetch) < ttl:
        return

    try:
        from opik.api_objects import opik_client

        client = opik_client.get_client_cached()
        config_client = ConfigClient(client.rest_client)
        config_id = object.__getattribute__(instance, "__opik_config_id__")
        field_types = object.__getattribute__(instance, "__opik_field_types__")
        env_val = object.__getattribute__(instance, "__opik_env__")

        if config_id is None:
            return

        config_data = config_client.get_blueprint(
            config_id=config_id,
            env=env_val,
            field_types=field_types,
        )
        _apply_backend_values(instance, config_data)
    except Exception:
        logger.debug("Failed to refetch config", exc_info=True)


def _refetch_with_mask(instance: typing.Any, mask_id: str) -> None:
    try:
        from opik.api_objects import opik_client

        client = opik_client.get_client_cached()
        config_client = ConfigClient(client.rest_client)
        config_id = object.__getattribute__(instance, "__opik_config_id__")
        field_types = object.__getattribute__(instance, "__opik_field_types__")

        if config_id is None:
            return

        config_data = config_client.get_blueprint(
            config_id=config_id,
            mask_id=mask_id,
            field_types=field_types,
        )
        _apply_backend_values(instance, config_data)
    except Exception:
        logger.debug("Failed to refetch config with mask", exc_info=True)


def _maybe_inject_span_metadata(instance: typing.Any) -> None:
    try:
        span_data = context_storage.top_span_data()
        if span_data is None:
            return

        config_id = object.__getattribute__(instance, "__opik_config_id__")
        blueprint_id = object.__getattribute__(instance, "__opik_blueprint_id__")
        values_cache = object.__getattribute__(instance, "__opik_values_cache__")

        config_metadata = {
            "opik_configs": {
                config_id: {
                    "blueprint_id": blueprint_id,
                    "values": dict(values_cache),
                }
            }
        }

        span_data.update(metadata=config_metadata)
    except Exception:
        logger.debug("Failed to inject config metadata into span", exc_info=True)
