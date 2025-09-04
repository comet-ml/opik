from __future__ import annotations

from typing import Any, Callable, Dict, Optional, Tuple, List

from ..optimization_config import chat_prompt


def make_opik_eval_fn(
    optimizer: Any,
    dataset: Any,
    metric: Callable[[Dict[str, Any], str], Any],
    n_samples: Optional[int],
) -> Callable[[Any], float]:
    """Create a scoring function for GEPA that evaluates a candidate using Opik metric."""

    def _eval_fn(candidate: Any, **_: Any) -> float:
        try:
            # candidate may be a dict {"system_prompt": text} or plain text
            if isinstance(candidate, dict):
                sys_text = next(iter(candidate.values()))
            else:
                sys_text = str(candidate)

            cp = chat_prompt.ChatPrompt(
                messages=[{"role": "system", "content": sys_text}],
                project_name=getattr(optimizer, "project_name", None),
                model=optimizer.model,
                **optimizer.model_kwargs,
            )
            s = optimizer.evaluate_prompt(  # type: ignore[attr-defined]
                prompt=cp,
                dataset=dataset,
                metric=metric,
                n_samples=n_samples,
                verbose=0,
            )
            return float(s)
        except Exception:
            return 0.0

    return _eval_fn


def build_adapter_if_available(
    gepa_module: Any,
    task_lm: str,
    reflection_lm: str,
    eval_fn: Callable[[Any], float],
) -> Optional[Any]:
    """Attempt to construct a GEPA DefaultAdapter (if available) and attach eval_fn.

    Returns the adapter instance or None if not available.
    """
    DefaultAdapter = None
    try:
        from gepa.adapters.default import DefaultAdapter as _DefaultAdapter  # type: ignore
        DefaultAdapter = _DefaultAdapter
    except Exception:
        try:
            from gepa.adapter.default import DefaultAdapter as _DefaultAdapter  # type: ignore
            DefaultAdapter = _DefaultAdapter
        except Exception:
            DefaultAdapter = None

    if DefaultAdapter is None:
        return None

    try:
        adapter = DefaultAdapter(task_lm=task_lm, reflection_lm=reflection_lm)  # type: ignore[call-arg]
    except TypeError:
        # Constructor signature mismatch; try minimal
        adapter = DefaultAdapter()  # type: ignore[call-arg]

    # Attach scoring function under common attribute names
    for attr in ("eval_fn", "score_fn", "objective_fn", "metric_fn", "scorer"):
        try:
            setattr(adapter, attr, eval_fn)
            break
        except Exception:
            continue

    # As last resort, override a generic evaluate method
    if not any(
        hasattr(adapter, attr)
        for attr in ("eval_fn", "score_fn", "objective_fn", "metric_fn", "scorer")
    ) and hasattr(adapter, "evaluate"):
        try:
            setattr(adapter, "evaluate", eval_fn)
        except Exception:
            pass

    return adapter

