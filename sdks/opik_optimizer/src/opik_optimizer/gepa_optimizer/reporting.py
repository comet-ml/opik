from contextlib import contextmanager
from typing import Any

from ..reporting_utils import (
    display_configuration,  # noqa: F401
    display_header,  # noqa: F401
    display_result,  # noqa: F401
    get_console,
    convert_tqdm_to_rich,
    suppress_opik_logs,
)

console = get_console()


@contextmanager
def baseline_evaluation(verbose: int = 1) -> Any:
    if verbose >= 1:
        console.print("> Establishing baseline performance (seed prompt)")
    class Reporter:
        def set_score(self, s: float) -> None:
            if verbose >= 1:
                console.print(f"  Baseline score: {s:.4f}")
    with suppress_opik_logs():
        with convert_tqdm_to_rich("  Evaluation", verbose=verbose):
            yield Reporter()


@contextmanager
def start_gepa_optimization(verbose: int = 1) -> Any:
    if verbose >= 1:
        console.print("> Starting GEPA optimization")
    class Reporter:
        def info(self, message: str) -> None:
            if verbose >= 1:
                console.print(f"│   {message}")
    try:
        yield Reporter()
    finally:
        if verbose >= 1:
            console.print("")

