import contextvars
from contextlib import contextmanager
from typing import Dict, Generator, Optional

_active_prompt_masks_var: contextvars.ContextVar[Optional[Dict[str, str]]] = (
    contextvars.ContextVar("opik_active_prompt_masks", default=None)
)


@contextmanager
def prompt_mask_context(masks: Optional[Dict[str, str]]) -> Generator[None, None, None]:
    token = _active_prompt_masks_var.set(masks)
    try:
        yield
    finally:
        _active_prompt_masks_var.reset(token)


def get_active_prompt_masks() -> Optional[Dict[str, str]]:
    return _active_prompt_masks_var.get()


def get_mask_for_prompt(prompt_id: str) -> Optional[str]:
    masks = _active_prompt_masks_var.get()
    if masks is None:
        return None
    return masks.get(prompt_id)
