"""Per-job context tracking using contextvars (works in both asyncio and threads)."""

import contextvars
from typing import Optional

_job_id_var: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "runner_job_id", default=None
)


def get_current_job_id() -> Optional[str]:
    return _job_id_var.get()


def set_job_id(job_id: str) -> contextvars.Token:
    return _job_id_var.set(job_id)


def reset_job_id(token: contextvars.Token) -> None:
    _job_id_var.reset(token)
