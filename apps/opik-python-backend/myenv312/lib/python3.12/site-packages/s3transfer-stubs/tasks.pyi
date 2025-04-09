"""
Type annotations for s3transfer.tasks module.

Copyright 2025 Vlad Emelianov
"""

import logging
from typing import Any, Callable, Sequence

from botocore.context import ClientContext
from s3transfer.futures import TransferCoordinator
from s3transfer.utils import get_callbacks as get_callbacks

logger: logging.Logger

class Task:
    def __init__(
        self,
        transfer_coordinator: TransferCoordinator,
        main_kwargs: dict[str, Any] | None = ...,
        pending_main_kwargs: dict[str, Any] | None = ...,
        done_callbacks: Sequence[Callable[..., Any]] | None = ...,
        is_final: bool = ...,
    ) -> None: ...
    @property
    def transfer_id(self) -> str: ...
    def __call__(self, ctx: ClientContext | None = None) -> None: ...

class SubmissionTask(Task): ...
class CreateMultipartUploadTask(Task): ...
class CompleteMultipartUploadTask(Task): ...
