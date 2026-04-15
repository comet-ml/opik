import contextvars
from contextlib import contextmanager
from typing import Optional, Generator

_active_config_mask_var: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "opik_active_config_mask", default=None
)

_active_config_blueprint_name_var: contextvars.ContextVar[Optional[str]] = (
    contextvars.ContextVar("opik_active_config_blueprint_name", default=None)
)


@contextmanager
def agent_config_context(
    mask_id: Optional[str],
    blueprint_name: Optional[str] = None,
) -> Generator[None, None, None]:
    mask_token = _active_config_mask_var.set(mask_id)
    blueprint_token = _active_config_blueprint_name_var.set(blueprint_name)
    try:
        yield
    finally:
        _active_config_mask_var.reset(mask_token)
        _active_config_blueprint_name_var.reset(blueprint_token)


def get_active_config_mask() -> Optional[str]:
    return _active_config_mask_var.get()


def get_active_config_blueprint_name() -> Optional[str]:
    return _active_config_blueprint_name_var.get()
