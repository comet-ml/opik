# ruff: noqa: F401 — re-exports for public API
import os

# ⚠️  MAINTAINER NOTE: Any new import MUST go inside the
#     `if not _LIGHTWEIGHT_MODE:` block below. See opik/__init__.py for details.
_LIGHTWEIGHT_MODE = os.environ.get("OPIK_SCORING_LIGHTWEIGHT") == "true"

if not _LIGHTWEIGHT_MODE:
    from .evaluator import (
        evaluate,
        evaluate_prompt,
        evaluate_experiment,
        evaluate_on_dict_items,
        evaluate_optimization_trial,
        run_tests,
    )
    from .threads.evaluator import evaluate_threads

__all__: list[str] = (
    []
    if _LIGHTWEIGHT_MODE
    else [
        "evaluate",
        "evaluate_prompt",
        "evaluate_experiment",
        "evaluate_on_dict_items",
        "evaluate_optimization_trial",
        "evaluate_threads",
        "run_tests",
    ]
)
