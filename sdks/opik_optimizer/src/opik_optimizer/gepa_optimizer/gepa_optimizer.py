import logging
import os
from typing import Any, Callable, Dict, List, Optional, Union, Tuple, Set, Sequence

import opik
from opik import Dataset
from opik.evaluation.metrics.score_result import ScoreResult

from ..base_optimizer import BaseOptimizer
from ..optimization_config import chat_prompt, mappers
from ..optimization_result import OptimizationResult
from ..utils import optimization_context, create_litellm_agent_class
from ..logging_config import setup_logging as _setup_logging
from .. import task_evaluator
from . import reporting as gepa_reporting
from .adapter import (
    make_opik_eval_fn,
    build_adapter_if_available,
    build_protocol_adapter,
    extract_candidate_system_text,
)

# Initialize logging using shared configuration
_setup_logging()

logger = logging.getLogger("opik_optimizer.gepa_optimizer")
_GEPA_DEBUG = bool(os.environ.get("OPIK_GEPA_DEBUG"))
if _GEPA_DEBUG:
    logger.setLevel(logging.DEBUG)


class GepaOptimizer(BaseOptimizer):
    """
    Wrapper around the external GEPA package (https://github.com/gepa-ai/gepa).

    This optimizer uses GEPA's DefaultAdapter to optimize a single system prompt for
    a single-turn task. It maps an Opik dataset + metric to GEPA's expected
    train/val set format and returns an OptimizationResult compatible with Opik's SDK.
    """

    def __init__(
        self,
        model: str,
        project_name: Optional[str] = None,
        reflection_model: Optional[str] = None,
        verbose: int = 1,
        **model_kwargs: Any,
    ) -> None:
        super().__init__(model=model, verbose=verbose, **model_kwargs)
        self.project_name = project_name
        # GEPA reflection LLM; defaults to same as task model if not provided
        self.reflection_model = reflection_model or model
        self.num_threads = self.model_kwargs.pop("num_threads", 6)
        # Debug counter: how many times live metric was invoked by GEPA
        self._gepa_live_metric_calls = 0
        self._gepa_current_iteration: Optional[int] = None
        self._gepa_candidate_records: List[Dict[str, Any]] = []
        self._gepa_candidate_seen: Set[Tuple[Tuple[str, str], ...]] = set()

    def _to_gepa_default_datainst(
        self,
        dataset_items: List[Dict[str, Any]],
        input_key: str,
        output_key: str,
    ) -> List[Dict[str, Any]]:
        """
        Convert Opik dataset items to GEPA DefaultAdapter DataInst entries.
        Each item is a dict with keys: input, answer, additional_context.
        """
        default_items: List[Dict[str, Any]] = []
        for item in dataset_items:
            addl_context: Dict[str, str] = {}
            # Try common places where context may be stored
            # tiny_test uses metadata["context"].
            meta = item.get("metadata") or {}
            if (
                isinstance(meta, dict)
                and "context" in meta
                and isinstance(meta["context"], str)
            ):
                addl_context["context"] = meta["context"]
            elif "context" in item and isinstance(item["context"], str):
                addl_context["context"] = item["context"]

            default_items.append(
                {
                    "input": str(item.get(input_key, "")),
                    "answer": str(item.get(output_key, "")),
                    "additional_context": addl_context,
                }
            )
        return default_items

    def _call_gepa_optimize(
        self,
        base_prompt: chat_prompt.ChatPrompt,
        seed_prompt_text: str,
        trainset: List[Dict[str, Any]],
        valset: Optional[List[Dict[str, Any]]],
        max_metric_calls: int,
        reflection_minibatch_size: int = 3,
        candidate_selection_strategy: str = "pareto",
        dataset: Optional[Dataset] = None,
        metric: Optional[Callable[[Dict[str, Any], str], ScoreResult]] = None,
        n_samples: Optional[int] = None,
        optimization_id: Optional[str] = None,
    ) -> Any:
        try:
            import gepa
            import inspect
        except Exception as e:  # pragma: no cover - only triggered if not installed
            raise ImportError(
                "gepa package is required for GepaOptimizer. Install with `pip install gepa`."
            ) from e

        # Use GEPA default adapter by passing task_lm as model string
        # reflection_lm must be provided when using DefaultAdapter
        optimize_sig = None
        try:
            optimize_sig = inspect.signature(gepa.optimize)
        except Exception:
            optimize_sig = None

        kwargs: Dict[str, Any] = dict(
            seed_candidate={"system_prompt": seed_prompt_text},
            trainset=trainset,
            valset=valset or trainset,
            task_lm=self.model,
            reflection_lm=self.reflection_model,
            candidate_selection_strategy=candidate_selection_strategy,
            reflection_minibatch_size=reflection_minibatch_size,
            max_metric_calls=max_metric_calls,
            display_progress_bar=False,
            track_best_outputs=False,
            logger=gepa_reporting.RichGEPAOptimizerLogger(self, verbose=self.verbose),
        )

        # If the installed GEPA supports a custom scoring function, inject our Opik metric
        def _make_eval_fn() -> Callable[[Any], float]:
            def _eval_fn(candidate: Any, **_: Any) -> float:
                try:
                    # candidate can be dict {"system_prompt": text} or text
                    sys_text = extract_candidate_system_text(candidate)
                    if not sys_text:
                        sys_text = str(candidate)
                    cp = chat_prompt.ChatPrompt(
                        messages=[{"role": "system", "content": sys_text}],
                        project_name=self.project_name,
                        model=self.model,
                        **self.model_kwargs,
                    )
                    s = super(GepaOptimizer, self).evaluate_prompt(  # type: ignore[misc]
                        prompt=cp,
                        dataset=dataset,  # type: ignore[arg-type]
                        metric=metric,  # type: ignore[arg-type]
                        n_threads=self.num_threads,
                        verbose=0,
                        n_samples=n_samples,
                    )
                    return float(s)
                except Exception:
                    return 0.0

            return _eval_fn

        # Preferred: Provide a custom adapter if supported
        self._reset_candidate_records()
        adapter_obj = None
        if dataset is not None and metric is not None:
            # Preferred: Build protocol adapter using Opik prompt + metric
            try:
                # Use the original prompt structure (preserve user/tools), but swap the system text
                base_cp = base_prompt.copy()
                base_cp.system = seed_prompt_text
                base_cp.project_name = self.project_name
                base_cp.model = self.model
                base_cp.model_kwargs = self.model_kwargs
                adapter_obj = build_protocol_adapter(
                    base_prompt=base_cp,
                    optimizer=self,
                    dataset=dataset,
                    metric=metric,
                    n_samples=n_samples,
                    optimization_id=optimization_id,
                )
                if _GEPA_DEBUG:
                    print(
                        f"[GEPA] Protocol adapter build: {'OK' if adapter_obj is not None else 'FAILED'}"
                    )
            except Exception as e:
                adapter_obj = None
                if _GEPA_DEBUG:
                    import traceback

                    print(f"[GEPA] Protocol adapter build ERROR: {e}")
                    traceback.print_exc()
                if os.environ.get("OPIK_GEPA_REQUIRE_PROTOCOL"):
                    raise
            # Fallback: try to patch DefaultAdapter
            if adapter_obj is None and not os.environ.get("OPIK_GEPA_REQUIRE_PROTOCOL"):
                eval_fn = make_opik_eval_fn(
                    self,
                    dataset,
                    metric,
                    n_samples,
                    optimization_id,
                    phase_label="gepa_adapter_eval",
                )
                adapter_obj = build_adapter_if_available(
                    gepa, self.model, self.reflection_model, eval_fn
                )
                if _GEPA_DEBUG:
                    print(
                        f"[GEPA] DefaultAdapter patch: {'OK' if adapter_obj is not None else 'FAILED'}"
                    )
            if adapter_obj is None and os.environ.get("OPIK_GEPA_REQUIRE_PROTOCOL"):
                raise RuntimeError(
                    "Protocol adapter required but could not be constructed."
                )

        used_live_metric = False
        if (
            optimize_sig
            and ("adapter" in optimize_sig.parameters)
            and adapter_obj is not None
        ):
            kwargs["adapter"] = adapter_obj
            # When providing an adapter, GEPA expects task_lm=None but still requires a reflection_lm
            kwargs["task_lm"] = None
            kwargs["reflection_lm"] = self.reflection_model
            used_live_metric = True
            if _GEPA_DEBUG:
                is_protocol = bool(
                    getattr(adapter_obj, "_is_opik_protocol_adapter", False)
                )
                kind = getattr(adapter_obj, "_opik_adapter_kind", "unknown")
                print(
                    f"[GEPA] Using protocol adapter path (adapter=…) protocol={is_protocol} kind={kind}"
                )
                if os.environ.get("OPIK_GEPA_REQUIRE_PROTOCOL") and not is_protocol:
                    raise RuntimeError(
                        "OPIK_GEPA_REQUIRE_PROTOCOL set, but non-protocol adapter was used."
                    )
        elif (
            optimize_sig
            and any(
                name in optimize_sig.parameters
                for name in (
                    "eval_fn",
                    "score_fn",
                    "objective_fn",
                    "metric_fn",
                    "scorer",
                )
            )
            and dataset is not None
            and metric is not None
        ):
            # Fallback: pass metric function directly if accepted
            kwargs[
                next(
                    name
                    for name in (
                        "eval_fn",
                        "score_fn",
                        "objective_fn",
                        "metric_fn",
                        "scorer",
                    )
                    if name in optimize_sig.parameters
                )
            ] = make_opik_eval_fn(
                self,
                dataset,
                metric,
                n_samples,
                optimization_id,
                phase_label="gepa_adapter_eval",
            )
            used_live_metric = True
            if _GEPA_DEBUG:
                print(
                    "[GEPA] Using eval_fn path (metric injected) — no protocol adapter"
                )
        else:
            if _GEPA_DEBUG:
                print(
                    "[GEPA] No adapter/eval_fn path available — GEPA will use its default scoring"
                )

        # Optional hard requirement for protocol adapter
        if (
            os.environ.get("OPIK_GEPA_REQUIRE_PROTOCOL")
            and kwargs.get("adapter") is None
        ):
            raise RuntimeError(
                "OPIK_GEPA_REQUIRE_PROTOCOL set, but protocol adapter is unavailable."
            )

        if self.verbose >= 1 or _GEPA_DEBUG:
            has_adapter = adapter_obj is not None
            msg = (
                f"Calling gepa.optimize(adapter={has_adapter}, max_metric_calls={max_metric_calls}, minibatch={reflection_minibatch_size}, "
                f"strategy={candidate_selection_strategy}, opt_id={optimization_id})"
            )
            if _GEPA_DEBUG:
                print("[GEPA] " + msg)
            msg2 = f"Live metric used during GEPA optimize: {used_live_metric}"
            if _GEPA_DEBUG:
                print("[GEPA] " + msg2)
        self._gepa_current_iteration = 0
        result = gepa.optimize(**kwargs)
        # Attach a hint flag to result if possible
        try:
            setattr(result, "_opik_used_live_metric", used_live_metric)
            setattr(
                result, "_opik_live_metric_calls", int(self._gepa_live_metric_calls)
            )
        except Exception:
            pass
        return result

    def gepa_evaluate_prompt(
        self,
        dataset: Union[str, Dataset],
        metric: Callable[[Dict[str, Any], str], ScoreResult],
        prompt: Optional[Union[chat_prompt.ChatPrompt, str, OptimizationResult]] = None,
        n_samples: Optional[int] = None,
        dataset_item_ids: Optional[List[str]] = None,
        experiment_config: Optional[Dict[str, Any]] = None,
        verbose: int = 1,
        **kwargs: Any,
    ) -> float:
        """
        Compute the score of a prompt on a subset of the dataset by delegating
        to BaseOptimizer.evaluate_prompt where possible. This is a convenience
        evaluator; GEPA's main entry point is optimize_prompt.
        """
        # Fallback to BaseOptimizer evaluation with a simple chat prompt
        if isinstance(dataset, str):
            client = opik.Opik(project_name=self.project_name)
            dataset = client.get_dataset(dataset)

        if isinstance(prompt, OptimizationResult):
            # If OptimizationResult provided, use its prompt messages
            prompt_messages = prompt.prompt
        elif isinstance(prompt, chat_prompt.ChatPrompt):
            prompt_messages = prompt.get_messages()
        elif isinstance(prompt, str):
            prompt_messages = [{"role": "system", "content": prompt}]
        else:
            raise ValueError("`prompt` must be provided for GEPA evaluation.")

        cp = chat_prompt.ChatPrompt(
            messages=prompt_messages,
            project_name=self.project_name,
            model=self.model,
            **self.model_kwargs,
        )

        score = super().evaluate_prompt(
            prompt=cp,
            dataset=dataset,
            metric=metric,
            n_threads=self.num_threads,
            verbose=verbose,
            dataset_item_ids=dataset_item_ids,
            experiment_config=experiment_config,
            n_samples=n_samples,
        )
        return score

    def _evaluate_prompt_logged(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: Dataset,
        metric: Callable[[Dict[str, Any], str], ScoreResult],
        n_samples: Optional[int] = None,
        dataset_item_ids: Optional[List[str]] = None,
        experiment_config: Optional[Dict[str, Any]] = None,
        optimization_id: Optional[str] = None,
        extra_metadata: Optional[Dict[str, Any]] = None,
        verbose: int = 1,
    ) -> float:
        # Ensure prompt has model settings
        if prompt.model is None:
            prompt.model = self.model
        if prompt.model_kwargs is None:
            prompt.model_kwargs = self.model_kwargs

        agent_class = create_litellm_agent_class(prompt)
        agent = agent_class(prompt)

        def llm_task(dataset_item: Dict[str, Any]) -> Dict[str, str]:
            messages = prompt.get_messages(dataset_item)
            raw = agent.invoke(messages)
            return {mappers.EVALUATED_LLM_TASK_OUTPUT: raw.strip()}

        experiment_config = experiment_config or {}
        experiment_config["project_name"] = agent_class.__name__
        experiment_config = {
            **experiment_config,
            **{
                "optimizer": self.__class__.__name__,
                "agent_class": agent_class.__name__,
                "agent_config": prompt.to_dict(),
                "metric": metric.__name__,
                "dataset": dataset.name,
                "configuration": {
                    "prompt": prompt.get_messages(),
                    "gepa": (extra_metadata or {}),
                },
            },
        }

        score = task_evaluator.evaluate(
            dataset=dataset,
            dataset_item_ids=dataset_item_ids,
            metric=metric,
            evaluated_task=llm_task,
            num_threads=self.num_threads,
            project_name=agent_class.project_name,
            experiment_config=experiment_config,
            optimization_id=optimization_id,
            n_samples=n_samples,
            verbose=verbose,
        )
        if self.verbose >= 1 and _GEPA_DEBUG:
            phase = (extra_metadata or {}).get("phase") if extra_metadata else None
            sys_text = self._extract_system_text(prompt)
            snippet = (sys_text or "").replace("\n", " ")[:140]
            logger.debug(
                f"[DBG][GEPA] Logged eval — phase={phase} opt_id={optimization_id} dataset={dataset.name} n_samples={n_samples or 'all'} score={score:.4f} prompt_snippet={snippet!r}"
            )
        return score

    def optimize_prompt(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: Union[str, Dataset],
        metric: Callable[[Dict[str, Any], str], ScoreResult],
        experiment_config: Optional[Dict[str, Any]] = None,
        **kwargs: Any,
    ) -> OptimizationResult:
        """
        Run GEPA optimization using DefaultAdapter over the given dataset and metric.

        Args:
            prompt: Seed `ChatPrompt` with a system and input mapping.
            dataset: Opik dataset name or object.
            metric: Opik metric function `(dataset_item, llm_output) -> ScoreResult`.
            max_metric_calls: GEPA budget for total metric calls across the run.
            reflection_minibatch_size: Batch size GEPA uses for reflective updates.
            candidate_selection_strategy: 'pareto' (default) or 'best'.
            n_samples: If provided, sub-sample the dataset for train/val.
            experiment_config: Extra metadata to record in results.
        """
        if isinstance(dataset, str):
            client = opik.Opik(project_name=self.project_name)
            dataset = client.get_dataset(dataset)

        # Extract config from kwargs with defaults
        max_metric_calls: int = int(kwargs.get("max_metric_calls", 30))
        reflection_minibatch_size: int = int(kwargs.get("reflection_minibatch_size", 3))
        candidate_selection_strategy: str = str(
            kwargs.get("candidate_selection_strategy", "pareto")
        )
        n_samples: Optional[int] = kwargs.get("n_samples")

        # Prepare seed prompt and data
        seed_prompt_text = self._extract_system_text(prompt)
        input_key, output_key = self._infer_dataset_keys(dataset)

        items = dataset.get_items()
        if n_samples is not None and n_samples > 0 and n_samples < len(items):
            # deterministic sub-sample for reproducibility
            items = items[:n_samples]

        trainset = self._to_gepa_default_datainst(items, input_key, output_key)
        valset = None  # default: GEPA uses trainset if valset is None

        # Pretty header and configuration will be displayed after obtaining optimization IDs
        initial_score: float = 0.0
        initial_prompt_messages = prompt.get_messages()

        # Track optimization run in Opik
        self._opik_client = opik.Opik(project_name=self.project_name)
        opt_id: Optional[str] = None
        ds_id: Optional[str] = getattr(dataset, "id", None)
        with optimization_context(
            client=self._opik_client,
            dataset_name=dataset.name,
            objective_name=metric.__name__,
            metadata={"optimizer": self.__class__.__name__},
        ) as optimization:
            # Now that we may have IDs, show header and config with link
            opt_id = None
            try:
                opt_id = optimization.id if optimization is not None else None
            except Exception:
                opt_id = None

            gepa_reporting.display_header(
                algorithm="GEPA",
                optimization_id=opt_id,
                dataset_id=getattr(dataset, "id", None),
                verbose=self.verbose,
            )
            from ..reporting_utils import display_configuration as _display_config

            _display_config(
                messages=prompt.get_messages(),
                optimizer_config={
                    "optimizer": "GEPA",
                    "model": self.model,
                    "reflection_model": self.reflection_model,
                    "max_metric_calls": max_metric_calls,
                    "reflection_minibatch_size": reflection_minibatch_size,
                    "candidate_selection_strategy": candidate_selection_strategy,
                    "n_samples": n_samples or "all",
                },
                verbose=self.verbose,
            )
            # Baseline evaluation tied to the optimization for tracking
            with gepa_reporting.baseline_evaluation(verbose=self.verbose) as baseline:
                try:
                    initial_score = float(
                        self._evaluate_prompt_logged(
                            prompt=prompt,
                            dataset=dataset,
                            metric=metric,
                            n_samples=n_samples,
                            optimization_id=opt_id,
                            extra_metadata={"phase": "baseline"},
                            verbose=0,
                        )
                    )
                    baseline.set_score(initial_score)
                except Exception:
                    pass
            with gepa_reporting.start_gepa_optimization(verbose=self.verbose):
                gepa_result = self._call_gepa_optimize(
                    base_prompt=prompt,
                    seed_prompt_text=seed_prompt_text,
                    trainset=trainset,
                    valset=valset,
                    max_metric_calls=max_metric_calls,
                    reflection_minibatch_size=reflection_minibatch_size,
                    candidate_selection_strategy=candidate_selection_strategy,
                    dataset=dataset,
                    metric=metric,
                    n_samples=n_samples,
                    optimization_id=opt_id,
                )
                try:
                    opt_id = optimization.id if optimization is not None else None
                except Exception:
                    opt_id = None

        # Build OptimizationResult (re-score candidates with Opik metric to pick best)
        candidates: List[Dict[str, str]] = getattr(gepa_result, "candidates", []) or []
        val_scores: List[float] = list(getattr(gepa_result, "val_aggregate_scores", []))

        rescored: List[float] = []
        recorded_lookup: Dict[Tuple[Tuple[str, str], ...], Dict[str, Any]] = {
            self._candidate_hash(entry["candidate"]): entry
            for entry in getattr(self, "_gepa_candidate_records", [])
        }

        # Merge in any recorded proposals GEPA didn't accept
        base_candidates = list(candidates)
        base_val_scores = list(val_scores)
        candidates = list(base_candidates)
        val_scores = list(base_val_scores)

        seen_hashes: Set[Tuple[Tuple[str, str], ...]] = {
            self._candidate_hash(cand) for cand in candidates
        }
        for entry in getattr(self, "_gepa_candidate_records", []):
            cand = entry["candidate"]
            h = self._candidate_hash(cand)
            if h in seen_hashes:
                continue
            agg_score = entry.get("aggregate_score")
            candidates.append(cand)
            val_scores.append(agg_score)
            seen_hashes.add(h)

        for i, cand in enumerate(candidates):
            cand_prompt_text = extract_candidate_system_text(cand) or seed_prompt_text
            # Preserve original user/tools and only swap system text
            cp = prompt.copy()
            cp.system = cand_prompt_text
            cp.project_name = self.project_name
            cp.model = self.model
            cp.model_kwargs = self.model_kwargs
            try:
                from ..reporting_utils import suppress_opik_logs as _suppress_logs

                with _suppress_logs():
                    s = self._evaluate_prompt_logged(
                        prompt=cp,
                        dataset=dataset,
                        metric=metric,
                        n_samples=n_samples,
                        optimization_id=opt_id,
                        extra_metadata={"phase": "rescoring", "candidate_index": i},
                        verbose=0,
                    )
            except Exception as e:
                if _GEPA_DEBUG:
                    logger.debug(f"[GEPA] Rescoring error for candidate {i}: {e}")
                s = 0.0
            rescored.append(float(s))

        # Choose best by Opik metric if we have rescored values; otherwise fallback to GEPA index
        if rescored:
            best_idx = max(range(len(rescored)), key=lambda idx: rescored[idx])
            score = rescored[best_idx]
        else:
            best_idx = getattr(gepa_result, "best_idx", 0) or 0
            score = float(val_scores[best_idx]) if val_scores else 0.0

        best_candidate = (
            candidates[best_idx]
            if candidates
            else getattr(gepa_result, "best_candidate", {})
        )
        best_prompt_text = (
            extract_candidate_system_text(best_candidate)
            if best_candidate
            else seed_prompt_text
        ) or seed_prompt_text

        # Prepare final prompt and log one last evaluation to ensure Opik UI reflects the chosen result
        final_cp = prompt.copy()
        final_cp.system = best_prompt_text
        final_cp.project_name = self.project_name
        final_cp.model = self.model
        final_cp.model_kwargs = self.model_kwargs
        try:
            from ..reporting_utils import suppress_opik_logs as _suppress_logs

            with _suppress_logs():
                _ = self._evaluate_prompt_logged(
                    prompt=final_cp,
                    dataset=dataset,
                    metric=metric,
                    n_samples=n_samples,
                    optimization_id=opt_id,
                    extra_metadata={"phase": "final", "selected": True},
                    verbose=0,
                )
        except Exception:
            pass

        # Build history with both GEPA and Opik rescoring where available
        candidate_rows: List[Dict[str, Any]] = []
        history: List[Dict[str, Any]] = []
        for i, cand in enumerate(candidates):
            cand_prompt = extract_candidate_system_text(cand) if cand else ""
            gepa_s = val_scores[i] if i < len(val_scores) else None
            opik_s = rescored[i] if i < len(rescored) else None
            cand_hash = self._candidate_hash(cand) if cand else None
            proposal_meta = recorded_lookup.get(cand_hash) if cand_hash else None
            source = (
                "GEPA" if i < len(base_candidates) else proposal_meta.get("phase", "Recorded") if proposal_meta else "Recorded"
            )
            iter_meta = proposal_meta.get("iteration") if proposal_meta else None
            if _GEPA_DEBUG:
                snippet = (cand_prompt or "").replace("\n", " ")[:120]
                logger.debug(
                    "[GEPA] Candidate %s source=%s gepa=%s opik=%s prompt=%r",
                    i + 1,
                    source,
                    f"{gepa_s:.4f}" if isinstance(gepa_s, (int, float)) else gepa_s,
                    f"{opik_s:.4f}" if isinstance(opik_s, (int, float)) else opik_s,
                    snippet,
                )
            candidate_rows.append(
                {
                    "iteration": iter_meta if iter_meta is not None else (self._gepa_current_iteration if source == "GEPA" else i + 1),
                    "system_prompt": cand_prompt,
                    "gepa_score": gepa_s,
                    "opik_score": opik_s,
                    "source": source,
                }
            )
            history.append(
                {
                    "iteration": i + 1,
                    "prompt_candidate": cand_prompt,
                    "scores": [
                        {"metric_name": f"GEPA-{metric.__name__}", "score": gepa_s},
                        {"metric_name": metric.__name__, "score": opik_s},
                    ],
                    "metadata": proposal_meta or {},
                }
            )

        if self.verbose >= 1:
            gepa_reporting.display_candidate_scores(
                candidate_rows,
                verbose=self.verbose,
            )
        elif _GEPA_DEBUG:
            logger.debug(
                "[GEPA] Candidate summary (count=%d recorded=%d)",
                len(candidate_rows),
                len(getattr(self, "_gepa_candidate_records", [])),
            )

        # Determine whether live metric was used inside GEPA:
        # Prefer flag propagated from _call_gepa_optimize, else fall back to counter
        used_live_metric_flag = bool(
            getattr(gepa_result, "_opik_used_live_metric", False)
        ) or bool(getattr(self, "_gepa_live_metric_calls", 0))

        details: Dict[str, Any] = {
            "model": self.model,
            "temperature": self.model_kwargs.get("temperature"),
            "optimizer": self.__class__.__name__,
            "num_candidates": getattr(gepa_result, "num_candidates", None),
            "total_metric_calls": getattr(gepa_result, "total_metric_calls", None),
            "val_scores": val_scores,
            "parents": gepa_result.parents,
            "gepa_live_metric_used": used_live_metric_flag,
            "gepa_live_metric_call_count": int(
                getattr(self, "_gepa_live_metric_calls", 0)
            ),
            "recorded_candidate_count": len(getattr(self, "_gepa_candidate_records", [])),
            "candidate_summary": candidate_rows,
            "best_candidate_iteration": candidate_rows[best_idx]["iteration"],
        }
        if experiment_config:
            details.update({"experiment": experiment_config})

        # Use the full prompt messages (system + user/tools) for result display
        final_messages = final_cp.get_messages()

        if self.verbose >= 1:
            gepa_reporting.display_selected_candidate(
                best_prompt_text,
                score,
                verbose=self.verbose,
            )
            if best_prompt_text.strip() == seed_prompt_text.strip():
                gepa_reporting.console.print(
                    "│   Selected prompt matches the seed prompt; improvement reflects rescoring statistics.",
                )
        elif _GEPA_DEBUG:
            logger.debug(
                "[GEPA] Selected candidate score=%.4f prompt_snippet=%r",
                score,
                (best_prompt_text or "").replace("\n", " ")[:160],
            )

        result = OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=final_messages,
            score=score,
            metric_name=metric.__name__,
            optimization_id=opt_id,
            dataset_id=ds_id,
            initial_prompt=initial_prompt_messages,
            initial_score=initial_score,
            details=details,
            history=history,
            llm_calls=None,  # not tracked for GEPA DefaultAdapter
        )

        return result

    def _extract_system_text(self, prompt: chat_prompt.ChatPrompt) -> str:
        msgs = prompt.get_messages()
        for m in msgs:
            if m.get("role") == "system":
                return str(m.get("content", "")).strip()
        # No explicit system; synthesize from first user message
        for m in msgs:
            if m.get("role") == "user":
                return f"You are a helpful assistant. Respond to: {m.get('content','')}"
        return "You are a helpful assistant."

    def _infer_dataset_keys(self, dataset: Dataset) -> Tuple[str, str]:
        """Heuristically infer input/output keys from dataset items."""
        items = dataset.get_items(1)
        if not items:
            return "text", "label"
        sample = items[0]
        # Prefer common output keys
        output_candidates = ["label", "answer", "output", "expected_output"]
        output_key = next((k for k in output_candidates if k in sample), "label")
        # Pick first non-output textual field as input
        excluded = set([output_key, "id", "metadata"])
        input_key = next((k for k in sample.keys() if k not in excluded), "text")
        return input_key, output_key

    def _reset_candidate_records(self) -> None:
        self._gepa_candidate_records = []
        self._gepa_candidate_seen = set()

    def _candidate_hash(self, candidate: Dict[str, Any]) -> Tuple[Tuple[str, str], ...]:
        items: List[Tuple[str, str]] = []
        for key, value in candidate.items():
            items.append((str(key), str(value)))
        return tuple(sorted(items))

    def _record_gepa_candidate(
        self,
        candidate: Dict[str, Any],
        scores: Sequence[float],
        phase: str = "proposal",
        iteration: Optional[int] = None,
    ) -> None:
        try:
            normalized = {str(k): str(v) for k, v in candidate.items()}
        except AttributeError:
            return
        key = self._candidate_hash(normalized)
        if key in self._gepa_candidate_seen:
            return
        self._gepa_candidate_seen.add(key)
        float_scores = [float(s) for s in scores if s is not None]
        aggregate = (
            sum(float_scores) / len(float_scores)
            if float_scores
            else None
        )
        record = {
            "candidate": normalized,
            "phase": phase,
            "subsample_scores": float_scores,
            "aggregate_score": aggregate,
            "iteration": iteration,
        }
        self._gepa_candidate_records.append(record)
        if self.verbose >= 1 and iteration is not None:
            gepa_reporting.display_candidate_update(
                iteration=iteration,
                phase=phase,
                aggregate=aggregate,
                prompt_snippet=normalized.get("system_prompt", ""),
                verbose=self.verbose,
            )
        if _GEPA_DEBUG:
            snippet = (normalized.get("system_prompt") or "").replace("\n", " ")[:120]
            logger.debug(
                "[GEPA] Recorded candidate phase=%s agg=%s scores=%s prompt=%r",
                phase,
                f"{aggregate:.4f}" if isinstance(aggregate, (int, float)) else aggregate,
                [f"{float(s):.4f}" for s in float_scores],
                snippet,
            )
