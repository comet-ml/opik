import logging
from contextlib import contextmanager
from functools import wraps
from typing import Any, TypeVar
from collections.abc import Callable

from rich.console import Console
from rich.progress import Progress

F = TypeVar("F", bound=Callable[..., Any])


_CONSOLE: Console | None = None


def get_console(*args: Any, **kwargs: Any) -> Console:
    global _CONSOLE
    if _CONSOLE is None:
        _CONSOLE = Console(*args, **kwargs)
        _CONSOLE.is_jupyter = False
    return _CONSOLE


@contextmanager
def convert_tqdm_to_rich(description: str | None = None, verbose: int = 1) -> Any:
    """Context manager to convert tqdm to rich progress bars."""
    import opik.evaluation.engine.evaluation_tasks_executor

    class _TqdmAdapter:
        """Minimal tqdm-like adapter backed by rich.Progress for Opik evaluators."""

        def __init__(
            self,
            iterable: Any | None,
            desc: str | None,
            total: int | None,
            disable: bool,
        ) -> None:
            self._iterable = iterable
            self._progress = Progress(transient=True, disable=disable)
            self._progress.start()
            self._task_id = self._progress.add_task(desc or "", total=total)

        def __iter__(self) -> Any:
            if self._iterable is None:
                self.close()
                return iter(())
            try:
                for item in self._iterable:
                    yield item
                    self.update(1)
            finally:
                self.close()

        def update(self, advance: int = 1) -> None:
            self._progress.advance(self._task_id, advance)

        @property
        def total(self) -> float | None:
            task = self._progress.tasks[self._task_id]
            return task.total

        @total.setter
        def total(self, value: int | None) -> None:
            self._progress.update(self._task_id, total=value)

        def close(self) -> None:
            self._progress.stop()

    def _tqdm_to_track(iterable: Any | None = None, *args: Any, **kwargs: Any) -> Any:
        desc = kwargs.get("desc")
        total = kwargs.get("total")
        disable = kwargs.get("disable", False) or verbose == 0
        if iterable is None and args:
            iterable = args[0]
        desc_value = description or (desc if isinstance(desc, str) else None) or ""
        return _TqdmAdapter(iterable, desc_value, total, disable)

    original__tqdm = opik.evaluation.engine.evaluation_tasks_executor._tqdm
    opik.evaluation.engine.evaluation_tasks_executor._tqdm = _tqdm_to_track  # type: ignore[assignment]

    try:
        yield
    finally:
        opik.evaluation.engine.evaluation_tasks_executor._tqdm = original__tqdm


def suppress_experiment_reporting(func: F) -> F:
    """Decorator to suppress opik experiment result/link display."""

    @wraps(func)
    def wrapper(*args: Any, **kwargs: Any) -> Any:
        from opik.evaluation import report

        original_results: Callable[..., None] = report.display_experiment_results
        original_link: Callable[..., None] = report.display_experiment_link

        def noop(*args: Any, **kwargs: Any) -> None:
            pass

        report.display_experiment_results = noop  # type: ignore[assignment]
        report.display_experiment_link = noop  # type: ignore[assignment]

        try:
            return func(*args, **kwargs)
        finally:
            report.display_experiment_results = original_results  # type: ignore[assignment]
            report.display_experiment_link = original_link  # type: ignore[assignment]

    return wrapper  # type: ignore[return-value]


@contextmanager
def suppress_opik_logs() -> Any:
    """Suppress Opik startup logs by temporarily increasing the log level."""
    # Get all loggers we need to suppress
    opik_client_logger = logging.getLogger("opik.api_objects.opik_client")
    opik_logger = logging.getLogger("opik")

    # Store original log levels
    original_client_level = opik_client_logger.level
    original_opik_level = opik_logger.level

    # Set log level to WARNING to suppress INFO messages
    opik_client_logger.setLevel(logging.WARNING)
    opik_logger.setLevel(logging.WARNING)

    try:
        yield
    finally:
        # Restore original log levels
        opik_client_logger.setLevel(original_client_level)
        opik_logger.setLevel(original_opik_level)
