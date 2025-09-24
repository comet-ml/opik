from __future__ import annotations

from typing import Any, Callable, Dict, Optional, List, Type
import logging
import os

from ..optimization_config import chat_prompt
from ..utils import create_litellm_agent_class
from ..logging_config import setup_logging as _setup_logging

_setup_logging()

logger = logging.getLogger("opik_optimizer.gepa_optimizer.adapter")
_GEPA_DEBUG = bool(os.environ.get("OPIK_GEPA_DEBUG"))

if _GEPA_DEBUG:
    logger.setLevel(logging.DEBUG)


def extract_candidate_system_text(candidate: Any) -> str:
    """Best-effort extraction of the system prompt text for a GEPA candidate."""
    preferred_fields = ("system_prompt", "system", "prompt")

    if candidate is None:
        return ""

    # Dict-like candidates expose prompt components under known keys
    if isinstance(candidate, dict):
        for key in preferred_fields:
            value = candidate.get(key)
            if isinstance(value, str) and value.strip():
                return value
        for value in candidate.values():
            if isinstance(value, str) and value.strip():
                return value
        if candidate:
            try:
                first_value = next(iter(candidate.values()))
                return str(first_value)
            except Exception:
                return ""

    # Object-style candidates may expose attributes directly
    for key in preferred_fields:
        try:
            value = getattr(candidate, key)
        except AttributeError:
            value = None
        if isinstance(value, str) and value.strip():
            return value

    return str(candidate)


def make_opik_eval_fn(
    optimizer: Any,
    dataset: Any,
    metric: Callable[[Dict[str, Any], str], Any],
    n_samples: Optional[int],
    optimization_id: Optional[str] = None,
    phase_label: Optional[str] = "gepa_adapter_eval",
    base_prompt: Optional["chat_prompt.ChatPrompt"] = None,
) -> Callable[[Any], float]:
    """Create a scoring function for GEPA that evaluates a candidate using Opik metric."""

    # Capture a template prompt that retains user messages, tools, and function wiring.
    prompt_template = base_prompt.copy() if base_prompt is not None else None

    def _eval_fn(candidate: Any, **_: Any) -> float:
        try:
            # candidate may be a dict {"system_prompt": text} or plain text
            sys_text = extract_candidate_system_text(candidate)
            if not sys_text:
                sys_text = str(candidate)

            if prompt_template is not None:
                cp = prompt_template.copy()
                if sys_text:
                    cp.system = sys_text
            else:
                cp = chat_prompt.ChatPrompt(
                    messages=[{"role": "system", "content": sys_text}],
                    project_name=getattr(optimizer, "project_name", None),
                    model=optimizer.model,
                    **optimizer.model_kwargs,
                )
            if _GEPA_DEBUG and getattr(optimizer, "verbose", 0) >= 1:
                snippet = (sys_text or "").replace("\n", " ")[:140]
                logger.debug(
                    f"[DBG][GEPA] Adapter eval (phase={phase_label}) — candidate system snippet: {snippet!r}"
                )
            # Prefer a logging-aware evaluator if available on the optimizer
            # Increment live-metric counter for diagnostics
            try:
                optimizer._gepa_live_metric_calls += 1  # type: ignore[attr-defined]
            except Exception:
                pass

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
            if hasattr(optimizer, "_record_gepa_candidate"):
                try:
                    candidate_map = {"system_prompt": sys_text}
                    optimizer._record_gepa_candidate(
                        candidate_map,
                        scores=[float(s)],
                        phase=str(phase_label or "eval_fn"),
                        iteration=getattr(optimizer, "_gepa_current_iteration", None),
                    )  # type: ignore[attr-defined]
                    if _GEPA_DEBUG:
                        logger.debug(
                            "[GEPA_ADAPTER] eval_fn recorded candidate phase=%s score=%.4f",
                            phase_label,
                            float(s),
                        )
                except Exception:
                    pass
            if _GEPA_DEBUG and getattr(optimizer, "verbose", 0) >= 1:
                logger.debug(
                    f"[DBG][GEPA] Adapter eval (phase={phase_label}) — score: {float(s):.4f}"
                )
            return float(s)
        except Exception as e:
            if _GEPA_DEBUG and getattr(optimizer, "verbose", 0) >= 1:
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
                # Marker and debug
                setattr(self, "_is_opik_protocol_adapter", False)
                setattr(self, "_opik_adapter_kind", "default_patched_subclass")

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
                    if _GEPA_DEBUG:
                        print("[GEPA_DEFAULT] evaluate() invoked (patched subclass)")
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
    # Marker and debug
    try:
        setattr(adapter, "_is_opik_protocol_adapter", False)
        setattr(adapter, "_opik_adapter_kind", "default_patched_attrs")
    except Exception:
        pass

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


def build_protocol_adapter(
    base_prompt: "chat_prompt.ChatPrompt",
    optimizer: Any,
    dataset: Any,
    metric: Callable[[Dict[str, Any], str], Any],
    n_samples: Optional[int] = None,
    optimization_id: Optional[str] = None,
) -> Optional[Any]:
    """
    Build a GEPAAdapter-conforming object that uses Opik's metric during GEPA iterations.
    Returns None if GEPA protocol types are unavailable.
    """
    try:
        from gepa.core.adapter import EvaluationBatch  # type: ignore
    except Exception as e:
        if _GEPA_DEBUG:
            print(f"[GEPA] Protocol types not importable, defining shim: {e}")

        # Define a shim with the same attribute names
        class EvaluationBatch:  # type: ignore
            def __init__(
                self,
                outputs: List[str],
                scores: List[float],
                trajectories: Optional[List[Dict[str, Any]]] = None,
            ) -> None:
                self.outputs = outputs
                self.scores = scores
                self.trajectories = trajectories

    class OpikGEPAAdapter:
        def __init__(self) -> None:
            # Marker so caller can distinguish protocol adapter vs. patched DefaultAdapter
            self._is_opik_protocol_adapter = True
            self._base_prompt = base_prompt.copy()
            # Use LiteLLM agent class for invocation
            self._agent_class = create_litellm_agent_class(self._base_prompt)
            self._agent = self._agent_class(self._base_prompt)
            # Retain optimizer/dataset/opt_id for optional traced eval
            self._optimizer = optimizer
            self._dataset = dataset
            self._opt_id = optimization_id
            if _GEPA_DEBUG:
                msg = "Initialized protocol adapter with base prompt and agent."
                try:
                    print("[GEPA_ADAPTER] " + msg)
                except Exception:
                    logger.info("[GEPA Adapter] " + msg)

        def _build_prompt_for_candidate(
            self, candidate: Dict[str, str]
        ) -> "chat_prompt.ChatPrompt":
            cp = self._base_prompt.copy()
            # Replace system prompt if provided
            sys_text = candidate.get("system_prompt") or candidate.get("system")
            if sys_text:
                cp.system = sys_text
            return cp

        def evaluate(
            self,
            batch: List[Dict[str, Any]],
            candidate: Dict[str, str],
            capture_traces: bool = False,
        ) -> Any:  # returns EvaluationBatch
            cp = self._build_prompt_for_candidate(candidate)
            if _GEPA_DEBUG:
                try:
                    print(
                        f"[GEPA_ADAPTER] evaluate() called: batch_size={len(batch)} capture_traces={capture_traces} candidate_keys={list(candidate.keys())}"
                    )
                    sys_snippet = (
                        candidate.get("system_prompt") or candidate.get("system") or ""
                    ).replace("\n", " ")[:160]
                    print(f"[GEPA_ADAPTER] candidate.system_prompt: {sys_snippet!r}")
                except Exception:
                    logger.info(
                        f"[GEPA Adapter] evaluate() called (batch_size={len(batch)})"
                    )
            # Optional traced evaluation through Opik evaluator (slower, but logs tools)
            # We still perform per-example execution to populate trajectories for reflection.
            outputs: List[str] = []
            scores: List[float] = []
            trajectories: Optional[List[Dict[str, Any]]] = (
                [] if capture_traces else None
            )
            if os.environ.get("OPIK_GEPA_TRACE_EVAL"):
                agg_score = 0.0
                try:
                    agg_score = float(
                        self._optimizer._evaluate_prompt_logged(  # type: ignore[attr-defined]
                            prompt=cp,
                            dataset=self._dataset,
                            metric=metric,
                            n_samples=len(batch) or None,
                            optimization_id=self._opt_id,
                            extra_metadata={"phase": "gepa_inloop_traced"},
                            verbose=0,
                        )
                    )
                except Exception as e:
                    if _GEPA_DEBUG:
                        print(f"[GEPA_ADAPTER] traced eval error: {e}")
                # Per-example execution for reflection and accurate per-item scores
                for di in batch:
                    input_text = str(di.get("input", ""))
                    dataset_item: Dict[str, str] = {
                        "text": input_text,
                        "question": input_text,
                        "input": input_text,
                        "label": str(di.get("answer", "")),
                        "answer": str(di.get("answer", "")),
                    }
                    try:
                        messages = cp.get_messages(dataset_item)
                        raw = self._agent.invoke(messages)
                        output = str(raw).strip()
                    except Exception as e:
                        if _GEPA_DEBUG:
                            logger.debug(f"[GEPA Adapter] invoke error (trace mode): {e}")
                        output = ""
                    try:
                        sr = metric(dataset_item, output)
                        # Try common attributes on ScoreResult
                        if hasattr(sr, "value"):
                            score = float(getattr(sr, "value"))
                        elif hasattr(sr, "score"):
                            score = float(getattr(sr, "score"))
                        else:
                            # Last resort: try casting if it supports it
                            try:
                                score = float(sr)  # type: ignore[arg-type]
                            except Exception:
                                score = 0.0
                    except Exception as e:
                        if _GEPA_DEBUG:
                            logger.debug(f"[GEPA Adapter] metric error (trace mode): {e}")
                        score = 0.0
                    # Mark live-metric usage
                    try:
                        self._optimizer._gepa_live_metric_calls += 1  # type: ignore[attr-defined]
                    except Exception:
                        pass
                    outputs.append(output)
                    scores.append(score)
                    if capture_traces and trajectories is not None:
                        trajectories.append(
                            {"input": input_text, "output": output, "score": score}
                        )
                if _GEPA_DEBUG:
                    try:
                        mean = (sum(scores) / len(scores)) if scores else 0.0
                        print(
                            f"[GEPA_ADAPTER] evaluate() traced: opik_sum={agg_score:.4f} per_item_sum={sum(scores):.4f} mean={mean:.4f} n={len(batch)}"
                        )
                    except Exception:
                        pass
                try:
                    if isinstance(candidate, dict):
                        candidate_map = {str(k): str(v) for k, v in candidate.items()}
                    else:
                        candidate_map = {"system_prompt": extract_candidate_system_text(candidate)}
                    self._optimizer._record_gepa_candidate(  # type: ignore[attr-defined]
                        candidate_map,
                        scores,
                        phase="adapter_evaluate_traced",
                        iteration=getattr(self._optimizer, "_gepa_current_iteration", None),
                    )
                    if _GEPA_DEBUG:
                        logger.debug(
                            "[GEPA_ADAPTER] recorded candidate phase=adapter_evaluate_traced scores=%s",
                            [f"{float(s):.4f}" for s in scores],
                        )
                except Exception as record_err:
                    if _GEPA_DEBUG:
                        print(f"[GEPA_ADAPTER] traced candidate record failed: {record_err}")

                return EvaluationBatch(
                    outputs=outputs, scores=scores, trajectories=trajectories
                )

            input_texts: List[str] = []
            for di in batch:
                input_text = str(di.get("input", ""))
                dataset_item = {
                    # Provide multiple aliases so placeholders resolve
                    "text": input_text,
                    "question": input_text,
                    "input": input_text,
                    # Provide both common label keys for metric
                    "label": str(di.get("answer", "")),
                    "answer": str(di.get("answer", "")),
                }
                try:
                    messages = cp.get_messages(dataset_item)
                    raw = self._agent.invoke(messages)
                    output = str(raw).strip()
                except Exception as e:
                    if _GEPA_DEBUG:
                        logger.debug(f"[GEPA Adapter] invoke error: {e}")
                    output = ""
                try:
                    sr = metric(dataset_item, output)
                    # Robust ScoreResult parsing: prefer .value, then .score, else cast
                    if hasattr(sr, "value"):
                        score = float(getattr(sr, "value"))
                    elif hasattr(sr, "score"):
                        score = float(getattr(sr, "score"))
                    else:
                        try:
                            score = float(sr)  # type: ignore[arg-type]
                        except Exception:
                            score = 0.0
                except Exception as e:
                    if _GEPA_DEBUG:
                        logger.debug(f"[GEPA Adapter] metric error: {e}")
                    score = 0.0
                # Mark live-metric usage even in non-traced mode
                try:
                    self._optimizer._gepa_live_metric_calls += 1  # type: ignore[attr-defined]
                except Exception:
                    pass
                outputs.append(output)
                scores.append(score)
                input_texts.append(input_text)
                if capture_traces and trajectories is not None:
                    trajectories.append(
                        {
                            "input": input_text,
                            "output": output,
                            "score": score,
                        }
                    )
            if _GEPA_DEBUG:
                try:
                    mean = (sum(scores) / len(scores)) if scores else 0.0
                    print(
                        f"[GEPA_ADAPTER] evaluate() done: sum={sum(scores):.4f} mean={mean:.4f} n={len(scores)}"
                    )
                except Exception:
                    logger.info("[GEPA Adapter] evaluate() done")
            batch_obj = EvaluationBatch(
                outputs=outputs, scores=scores, trajectories=trajectories
            )
            # Attach inputs for downstream reflection fallback if supported
            try:
                setattr(batch_obj, "inputs", input_texts)
            except Exception:
                pass
            try:
                if not capture_traces:
                    if isinstance(candidate, dict):
                        candidate_map = {str(k): str(v) for k, v in candidate.items()}
                    else:
                        candidate_map = {"system_prompt": extract_candidate_system_text(candidate)}
                    self._optimizer._record_gepa_candidate(  # type: ignore[attr-defined]
                        candidate_map,
                        scores,
                        phase="adapter_evaluate",
                        iteration=getattr(self._optimizer, "_gepa_current_iteration", None),
                    )
                    if _GEPA_DEBUG:
                        logger.debug(
                            "[GEPA_ADAPTER] recorded candidate phase=adapter_evaluate scores=%s",
                            [f"{float(s):.4f}" for s in scores],
                        )
            except Exception as record_err:
                if _GEPA_DEBUG:
                    print(f"[GEPA_ADAPTER] candidate record failed: {record_err}")
            return batch_obj

        def make_reflective_dataset(
            self,
            candidate: Dict[str, str],
            eval_batch: Any,
            components_to_update: List[str],
        ) -> Dict[str, List[Dict[str, Any]]]:
            # Build minimal reflective data using trajectories if present
            reflective: Dict[str, List[Dict[str, Any]]] = {}
            comp = components_to_update or ["system_prompt"]
            records: List[Dict[str, Any]] = []
            traj = getattr(eval_batch, "trajectories", None)
            if traj:
                for t in traj:
                    records.append(
                        {
                            "Inputs": {"text": t.get("input", "")},
                            "Generated Outputs": t.get("output", ""),
                            "Feedback": f"score={t.get('score', 0.0):.4f}",
                        }
                    )
            else:
                # Fall back to outputs/scores only
                inputs_fallback = list(getattr(eval_batch, "inputs", [])) or [""] * len(
                    getattr(eval_batch, "outputs", [])
                )
                for inp, out, sc in zip(
                    inputs_fallback,
                    getattr(eval_batch, "outputs", []),
                    getattr(eval_batch, "scores", []),
                ):
                    records.append(
                        {
                            "Inputs": {"text": str(inp)[:200]},
                            "Generated Outputs": str(out)[:200],
                            "Feedback": f"score={float(sc):.4f}",
                        }
                    )
            for c in comp:
                reflective[c] = records
            if _GEPA_DEBUG:
                logger.info(
                    f"[GEPA Adapter] make_reflective_dataset() components={comp} records={len(records)}"
                )
            return reflective

        # Optional: leave propose_new_texts to GEPA's default mechanism
        propose_new_texts = None  # type: ignore

    return OpikGEPAAdapter()
