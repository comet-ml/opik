import contextvars
from contextlib import contextmanager
from typing import Optional, Generator

_active_config_mask_var: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "opik_active_config_mask", default=None
)


@contextmanager
def agent_config_context(mask_id: str) -> Generator[None, None, None]:
    token = _active_config_mask_var.set(mask_id)
    try:
        yield
    finally:
        _active_config_mask_var.reset(token)


def get_active_config_mask() -> Optional[str]:
    return _active_config_mask_var.get()
