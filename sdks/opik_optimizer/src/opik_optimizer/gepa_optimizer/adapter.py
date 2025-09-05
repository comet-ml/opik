from __future__ import annotations

from typing import Any, Callable, Dict, Optional, List, Type
import logging

from ..optimization_config import chat_prompt

logger = logging.getLogger(__name__)


def make_opik_eval_fn(
    optimizer: Any,
    dataset: Any,
    metric: Callable[[Dict[str, Any], str], Any],
    n_samples: Optional[int],
    optimization_id: Optional[str] = None,
    phase_label: Optional[str] = "gepa_adapter_eval",
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
            if getattr(optimizer, "verbose", 0) >= 1:
                snippet = (sys_text or "").replace("\n", " ")[:140]
                logger.debug(
                    f"[DBG][GEPA] Adapter eval (phase={phase_label}) — candidate system snippet: {snippet!r}"
                )
            # Prefer a logging-aware evaluator if available on the optimizer
            if hasattr(optimizer, "_evaluate_prompt_logged"):
                s = optimizer._evaluate_prompt_logged(  # type: ignore[attr-defined]
                    prompt=cp,
                    dataset=dataset,
                    metric=metric,
                    n_samples=n_samples,
                    verbose=0,
                    optimization_id=optimization_id,
                    extra_metadata={"phase": phase_label},
                )
            else:
                s = optimizer.evaluate_prompt(  # type: ignore[attr-defined]
                    prompt=cp,
                    dataset=dataset,
                    metric=metric,
                    n_samples=n_samples,
                    verbose=0,
                )
            if getattr(optimizer, "verbose", 0) >= 1:
                logger.debug(
                    f"[DBG][GEPA] Adapter eval (phase={phase_label}) — score: {float(s):.4f}"
                )
            return float(s)
        except Exception as e:
            if getattr(optimizer, "verbose", 0) >= 1:
                logger.debug(f"[DBG][GEPA] Adapter eval error: {e}")
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
            try:
                # Newer GEPA layout
                from gepa.adapters.default_adapter.default_adapter import (
                    DefaultAdapter as _DefaultAdapter,  # type: ignore
                )

                DefaultAdapter = _DefaultAdapter
            except Exception:
                DefaultAdapter = None

    if DefaultAdapter is None:
        return None

    # Build an adapter subclass that overrides evaluate() to use our Opik metric
    OpikAdapter: Optional[Type[Any]] = None
    try:
        # Import EvaluationBatch type if available to construct correct return types
        try:
            from gepa.core.adapter import EvaluationBatch as _EvaluationBatch  # type: ignore
        except Exception:
            _EvaluationBatch = None  # type: ignore

        # Help static type checkers: use an alias for dynamic base type
        DefaultAdapterType: Any = DefaultAdapter  # type: ignore[assignment]

        class _OpikAdapter(DefaultAdapterType):  # type: ignore[misc, valid-type]
            def __init__(self, *args: Any, **kwargs: Any) -> None:  # noqa: ANN401
                # Ensure model is provided to base DefaultAdapter
                try:
                    super().__init__(model=task_lm)  # type: ignore[misc]
                except TypeError:
                    super().__init__(task_lm)  # type: ignore[misc]
                self._opik_eval_fn = eval_fn

            # GEPA adapters typically expose an evaluate() or score() API
            # Match DefaultAdapter signature: evaluate(batch, candidate, capture_traces=False)
            def evaluate(
                self,
                batch: List[Dict[str, Any]],
                candidate: Any,
                *args: Any,
                **kwargs: Any,
            ) -> Any:  # noqa: ANN401
                # Trigger Opik-logged evaluation as a side effect for observability
                try:
                    _ = self._opik_eval_fn(candidate)
                except Exception:
                    pass
                # Delegate to the base DefaultAdapter to produce proper trajectories for reflection
                return super().evaluate(batch, candidate, *args, **kwargs)

            def score(self, candidate: Any, *args: Any, **kwargs: Any) -> float:  # noqa: ANN401
                return float(self._opik_eval_fn(candidate))

        OpikAdapter = _OpikAdapter
    except Exception:
        OpikAdapter = None

    if OpikAdapter is not None:
        try:
            # Try passing task/reflection LMs similar to anymaths adapter style
            adapter = OpikAdapter()  # __init__ handles model
            return adapter
        except Exception:
            pass

    # Fallback: instantiate DefaultAdapter and attach eval_fn as attributes
    try:
        adapter = DefaultAdapter(model=task_lm)  # type: ignore[call-arg]
    except TypeError:
        adapter = DefaultAdapter(task_lm)  # type: ignore[call-arg]

    for attr in ("eval_fn", "score_fn", "objective_fn", "metric_fn", "scorer"):
        try:
            setattr(adapter, attr, eval_fn)
            break
        except Exception:
            continue

    if not any(
        hasattr(adapter, attr)
        for attr in ("eval_fn", "score_fn", "objective_fn", "metric_fn", "scorer")
    ) and hasattr(adapter, "evaluate"):
        try:
            setattr(adapter, "evaluate", eval_fn)
        except Exception:
            pass

    return adapter
