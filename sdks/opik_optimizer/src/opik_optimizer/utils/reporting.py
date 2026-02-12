import base64
import logging
import time
import urllib.parse
from numbers import Real
from contextlib import contextmanager
from functools import wraps
from typing import Any, Final, TypeVar
from collections.abc import Callable

import opik
import opik.config
from rich.console import Console
from rich.progress import Progress, TextColumn

F = TypeVar("F", bound=Callable[..., Any])


_CONSOLE: Console | None = None


# Global console instance for rich progress bars.
def get_console(*args: Any, **kwargs: Any) -> Console:
    """Get the global console instance for rich progress bars."""
    global _CONSOLE
    if _CONSOLE is None:
        _CONSOLE = Console(*args, **kwargs)
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
            self._base_description = desc or ""
            self._progress = Progress(
                *Progress.get_default_columns(),
                TextColumn("{task.fields[postfix]}", justify="right"),
                transient=True,
                disable=disable,
                console=get_console(),
            )
            self._progress.start()
            self._task_id = self._progress.add_task(
                self._base_description, total=total, postfix=""
            )
            self._last_heartbeat = time.time()
            self._logger = logging.getLogger("opik_optimizer")
            self._heartbeat_interval = 5.0

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
            """Update the progress bar with heartbeat logging."""
            self._progress.advance(self._task_id, advance)
            if not self._logger.isEnabledFor(logging.DEBUG):
                return
            if not self._progress.disable:
                # Avoid interleaving debug logs with active progress bars.
                return
            now = time.time()
            # Don't log more frequently than every 5 seconds
            if now - self._last_heartbeat < self._heartbeat_interval:
                return
            task = self._progress.tasks[self._task_id]
            total = task.total
            completed = task.completed
            if total:
                self._logger.debug(
                    "evaluation progress: %s/%s (%.1f%%)",
                    int(completed),
                    int(total),
                    (completed / total) * 100,
                )
            else:
                self._logger.debug("evaluation progress: %s", int(completed))
            self._last_heartbeat = now

        @property
        def total(self) -> float | None:
            """Get the total number of items to be processed."""
            task = self._progress.tasks[self._task_id]
            return task.total

        @total.setter
        def total(self, value: int | None) -> None:
            """Set the total number of items to be processed."""
            self._progress.update(self._task_id, total=value)

        def set_postfix(self, ordered_dict: Any | None = None, **kwargs: Any) -> None:
            """
            Set postfix metadata in a tqdm-compatible way.

            Rich does not provide a direct postfix API, so we append formatted
            key/value pairs to the task description.
            """
            postfix: dict[str, Any] = {}
            if ordered_dict is not None:
                if isinstance(ordered_dict, dict):
                    postfix.update(ordered_dict)
                else:
                    try:
                        postfix.update(dict(ordered_dict))
                    except (TypeError, ValueError):
                        pass
            postfix.update(kwargs)
            postfix_text = " | ".join(
                f"[dim]{key}:[/] {self._format_postfix_value(value)}"
                for key, value in postfix.items()
            )
            self._progress.update(self._task_id, postfix=postfix_text)

        @staticmethod
        def _format_postfix_value(value: Any) -> str:
            """
            Format values for postfix display.

            Preformatted strings are preserved exactly so SDK-side formatting
            (for example, score averages formatted to 4 decimals) is not lost.
            """
            if isinstance(value, Real) and not isinstance(value, bool):
                return f"{float(value):.2f}".rstrip("0").rstrip(".")
            if isinstance(value, str):
                return value
            return str(value)

        def close(self) -> None:
            """Stop the progress bar."""
            self._progress.stop()

    def _tqdm_to_track(iterable: Any | None = None, *args: Any, **kwargs: Any) -> Any:
        """Convert tqdm to rich progress bars."""
        desc = kwargs.get("desc")
        total = kwargs.get("total")
        disable = kwargs.get("disable", False) or verbose == 0
        if iterable is None and args:
            iterable = args[0]
        desc_value = description or (desc if isinstance(desc, str) else None) or ""
        return _TqdmAdapter(iterable, desc_value, total, disable)

    # Convert tqdm to rich progress bars
    original__tqdm = getattr(
        opik.evaluation.engine.evaluation_tasks_executor, "_tqdm", None
    )
    original_tqdm = getattr(
        opik.evaluation.engine.evaluation_tasks_executor, "tqdm", None
    )
    opik.evaluation.engine.evaluation_tasks_executor._tqdm = _tqdm_to_track  # type: ignore[assignment]
    if original_tqdm is not None:
        opik.evaluation.engine.evaluation_tasks_executor.tqdm = _tqdm_to_track  # type: ignore[assignment]

    try:
        yield
    finally:
        # Restore original tqdm implementation
        if original__tqdm is not None:
            opik.evaluation.engine.evaluation_tasks_executor._tqdm = original__tqdm
        if original_tqdm is not None:
            opik.evaluation.engine.evaluation_tasks_executor.tqdm = original_tqdm


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


ALLOWED_URL_CHARACTERS: Final[str] = ":/&?="


def ensure_ending_slash(url: str) -> str:
    """Ensure a URL ends with a trailing slash."""
    return url.rstrip("/") + "/"


def get_optimization_run_url_by_id(
    dataset_id: str | None, optimization_id: str | None
) -> str:
    """
    Generate an optimization run URL for display in the Opik dashboard.

    Args:
        dataset_id: The dataset ID for the optimization run.
        optimization_id: The optimization ID for the run.

    Returns:
        The full URL to view the optimization run in the Opik dashboard.

    Raises:
        ValueError: If either dataset_id or optimization_id is None.
    """
    if dataset_id is None or optimization_id is None:
        raise ValueError(
            "Cannot create a new run link without a dataset_id and optimization_id."
        )

    opik_config = opik.config.get_from_user_inputs()
    url_override = opik_config.url_override
    encoded_opik_url = base64.b64encode(url_override.encode("utf-8")).decode("utf-8")

    run_path = urllib.parse.quote(
        f"v1/session/redirect/optimizations/?optimization_id={optimization_id}&dataset_id={dataset_id}&path={encoded_opik_url}",
        safe=ALLOWED_URL_CHARACTERS,
    )
    return urllib.parse.urljoin(ensure_ending_slash(url_override), run_path)
