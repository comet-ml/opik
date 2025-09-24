from __future__ import annotations

import logging
from contextlib import nullcontext
from typing import Any, Callable, ContextManager, Dict, List, Optional, Tuple, Union

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
from .adapter import OpikDataInst, OpikGEPAAdapter


_setup_logging()
LOGGER = logging.getLogger("opik_optimizer.gepa.optimizer")


class GepaOptimizer(BaseOptimizer):
    """Minimal integration against the upstream GEPA engine."""

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
        self.reflection_model = reflection_model or model
        self.num_threads = self.model_kwargs.pop("num_threads", 6)
        self._gepa_live_metric_calls = 0

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _build_data_insts(
        self,
        dataset_items: List[Dict[str, Any]],
        input_key: str,
        output_key: str,
    ) -> List[OpikDataInst]:
        data_insts: List[OpikDataInst] = []
        for item in dataset_items:
            additional_context: Dict[str, str] = {}
            metadata = item.get("metadata") or {}
            if isinstance(metadata, dict):
                context_value = metadata.get("context")
                if isinstance(context_value, str):
                    additional_context["context"] = context_value
            if "context" in item and isinstance(item["context"], str):
                additional_context.setdefault("context", item["context"])

            data_insts.append(
                OpikDataInst(
                    input_text=str(item.get(input_key, "")),
                    answer=str(item.get(output_key, "")),
                    additional_context=additional_context,
                    opik_item=item,
                )
            )
        return data_insts

    def _apply_system_text(
        self, prompt_obj: chat_prompt.ChatPrompt, system_text: str
    ) -> chat_prompt.ChatPrompt:
        updated = prompt_obj.copy()
        if updated.messages is not None:
            messages = updated.get_messages()
            if messages and messages[0].get("role") == "system":
                messages[0]["content"] = system_text
            else:
                messages.insert(0, {"role": "system", "content": system_text})
            updated.set_messages(messages)
        else:
            updated.system = system_text
        return updated

    def _infer_dataset_keys(self, dataset: Dataset) -> Tuple[str, str]:
        items = dataset.get_items(1)
        if not items:
            return "text", "label"
        sample = items[0]
        output_candidates = ["label", "answer", "output", "expected_output"]
        output_key = next((k for k in output_candidates if k in sample), "label")
        excluded = {output_key, "id", "metadata"}
        input_key = next((k for k in sample.keys() if k not in excluded), "text")
        return input_key, output_key

    # ------------------------------------------------------------------
    # Base optimizer overrides
    # ------------------------------------------------------------------

    def optimize_prompt(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: Union[str, Dataset],
        metric: Callable[[Dict[str, Any], str], ScoreResult],
        experiment_config: Optional[Dict[str, Any]] = None,
        **kwargs: Any,
    ) -> OptimizationResult:
        if isinstance(dataset, str):
            client = opik.Opik(project_name=self.project_name)
            dataset = client.get_dataset(dataset)

        max_metric_calls: int = int(kwargs.get("max_metric_calls", 30))
        reflection_minibatch_size: int = int(kwargs.get("reflection_minibatch_size", 3))
        candidate_selection_strategy: str = str(
            kwargs.get("candidate_selection_strategy", "pareto")
        )
        n_samples: Optional[int] = kwargs.get("n_samples")

        prompt = prompt.copy()
        if self.project_name:
            prompt.project_name = self.project_name
        if prompt.model is None:
            prompt.model = self.model
        if not prompt.model_kwargs:
            prompt.model_kwargs = dict(self.model_kwargs)

        seed_prompt_text = self._extract_system_text(prompt)
        input_key, output_key = self._infer_dataset_keys(dataset)

        items = dataset.get_items()
        if n_samples and 0 < n_samples < len(items):
            items = items[:n_samples]

        data_insts = self._build_data_insts(items, input_key, output_key)

        self._gepa_live_metric_calls = 0

        base_prompt = prompt.copy()

        opt_id: Optional[str] = None
        ds_id: Optional[str] = getattr(dataset, "id", None)

        opik_client = opik.Opik(project_name=self.project_name)

        with optimization_context(
            client=opik_client,
            dataset_name=dataset.name,
            objective_name=metric.__name__,
            metadata={"optimizer": self.__class__.__name__},
        ) as optimization:
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

            # Baseline evaluation
            initial_prompt_messages = prompt.get_messages()
            initial_score = 0.0
            with gepa_reporting.baseline_evaluation(verbose=self.verbose) as baseline:
                try:
                    baseline_suppress: ContextManager[Any] = nullcontext()
                    try:
                        from ..reporting_utils import (
                            suppress_opik_logs as _suppress_logs,
                        )

                        baseline_suppress = _suppress_logs()
                    except Exception:
                        pass
                    eval_kwargs = dict(
                        prompt=prompt,
                        dataset=dataset,
                        metric=metric,
                        n_samples=n_samples,
                        optimization_id=opt_id,
                        extra_metadata={"phase": "baseline"},
                        verbose=0,
                    )
                    with baseline_suppress:
                        initial_score = float(
                            self._evaluate_prompt_logged(**eval_kwargs)
                        )
                    baseline.set_score(initial_score)
                except Exception:
                    LOGGER.exception("Baseline evaluation failed")

            adapter_prompt = self._apply_system_text(base_prompt, seed_prompt_text)
            adapter_prompt.project_name = self.project_name
            adapter_prompt.model = self.model
            adapter_prompt.model_kwargs = self.model_kwargs

            adapter = OpikGEPAAdapter(
                base_prompt=adapter_prompt,
                optimizer=self,
                metric=metric,
                system_fallback=seed_prompt_text,
            )

            try:
                import gepa
                import inspect
            except Exception as exc:  # pragma: no cover
                raise ImportError("gepa package is required for GepaOptimizer") from exc

            kwargs_gepa: Dict[str, Any] = {
                "seed_candidate": {"system_prompt": seed_prompt_text},
                "trainset": data_insts,
                "valset": data_insts,
                "adapter": adapter,
                "task_lm": None,
                "reflection_lm": self.reflection_model,
                "candidate_selection_strategy": candidate_selection_strategy,
                "reflection_minibatch_size": reflection_minibatch_size,
                "max_metric_calls": max_metric_calls,
                "display_progress_bar": False,
                "track_best_outputs": False,
                "logger": gepa_reporting.RichGEPAOptimizerLogger(
                    self, verbose=self.verbose
                ),
            }

            optimize_sig = None
            try:
                optimize_sig = inspect.signature(gepa.optimize)
            except Exception:
                optimize_sig = None

            if optimize_sig and "stop_callbacks" not in optimize_sig.parameters:
                kwargs_gepa["max_metric_calls"] = max_metric_calls

            with gepa_reporting.start_gepa_optimization(verbose=self.verbose):
                gepa_result = gepa.optimize(**kwargs_gepa)

            try:
                opt_id = optimization.id if optimization is not None else None
            except Exception:
                opt_id = None

        # ------------------------------------------------------------------
        # Rescoring & result assembly
        # ------------------------------------------------------------------

        candidates: List[Dict[str, str]] = getattr(gepa_result, "candidates", []) or []
        val_scores: List[float] = list(getattr(gepa_result, "val_aggregate_scores", []))

        rescored: List[float] = []
        candidate_rows: List[Dict[str, Any]] = []
        history: List[Dict[str, Any]] = []

        for idx, candidate in enumerate(candidates):
            candidate_prompt = self._extract_system_text_from_candidate(
                candidate, seed_prompt_text
            )
            prompt_variant = self._apply_system_text(prompt, candidate_prompt)
            prompt_variant.project_name = self.project_name
            prompt_variant.model = self.model
            prompt_variant.model_kwargs = self.model_kwargs

            eval_kwargs = dict(
                prompt=prompt_variant,
                dataset=dataset,
                metric=metric,
                n_samples=n_samples,
                optimization_id=opt_id,
                extra_metadata={"phase": "rescoring", "candidate_index": idx},
                verbose=0,
            )
            try:
                score = float(self._evaluate_prompt_logged(**eval_kwargs))
            except Exception:
                LOGGER.debug("Rescoring failed for candidate %s", idx, exc_info=True)
                score = 0.0

            rescored.append(score)
            candidate_rows.append(
                {
                    "iteration": idx + 1,
                    "system_prompt": candidate_prompt,
                    "gepa_score": val_scores[idx] if idx < len(val_scores) else None,
                    "opik_score": score,
                    "source": "GEPA",
                }
            )
            history.append(
                {
                    "iteration": idx + 1,
                    "prompt_candidate": candidate_prompt,
                    "scores": [
                        {
                            "metric_name": f"GEPA-{metric.__name__}",
                            "score": val_scores[idx] if idx < len(val_scores) else None,
                        },
                        {"metric_name": metric.__name__, "score": score},
                    ],
                    "metadata": {},
                }
            )

        if rescored:
            best_idx = max(range(len(rescored)), key=lambda i: rescored[i])
            best_score = rescored[best_idx]
        else:
            best_idx = getattr(gepa_result, "best_idx", 0) or 0
            best_score = float(val_scores[best_idx]) if val_scores else 0.0

        best_candidate = (
            candidates[best_idx] if candidates else {"system_prompt": seed_prompt_text}
        )
        best_prompt_text = self._extract_system_text_from_candidate(
            best_candidate, seed_prompt_text
        )

        final_prompt = self._apply_system_text(prompt, best_prompt_text)
        final_prompt.project_name = self.project_name
        final_prompt.model = self.model
        final_prompt.model_kwargs = self.model_kwargs

        final_eval_kwargs = dict(
            prompt=final_prompt,
            dataset=dataset,
            metric=metric,
            n_samples=n_samples,
            optimization_id=opt_id,
            extra_metadata={"phase": "final", "selected": True},
            verbose=0,
        )
        suppress_logs: ContextManager[Any] = nullcontext()
        try:
            from ..reporting_utils import suppress_opik_logs as _suppress_logs

            suppress_logs = _suppress_logs()
        except Exception:
            pass

        with suppress_logs:
            try:
                self._evaluate_prompt_logged(**final_eval_kwargs)
            except Exception:
                LOGGER.debug("Final evaluation failed", exc_info=True)

        per_item_scores: List[Dict[str, Any]] = []
        try:
            analysis_prompt = final_prompt.copy()
            agent_cls = create_litellm_agent_class(analysis_prompt)
            agent = agent_cls(analysis_prompt)
            for item in items:
                messages = analysis_prompt.get_messages(item)
                output_text = agent.invoke(messages).strip()
                metric_result = metric(item, output_text)
                if hasattr(metric_result, "value"):
                    score_val = float(metric_result.value)
                elif hasattr(metric_result, "score"):
                    score_val = float(metric_result.score)
                else:
                    score_val = float(metric_result)
                per_item_scores.append(
                    {
                        "dataset_item_id": item.get("id"),
                        "score": score_val,
                        "answer": item.get(output_key),
                        "output": output_text,
                    }
                )
        except Exception:
            LOGGER.debug("Per-item diagnostics failed", exc_info=True)

        details: Dict[str, Any] = {
            "model": self.model,
            "temperature": self.model_kwargs.get("temperature"),
            "optimizer": self.__class__.__name__,
            "num_candidates": getattr(gepa_result, "num_candidates", None),
            "total_metric_calls": getattr(gepa_result, "total_metric_calls", None),
            "parents": getattr(gepa_result, "parents", None),
            "val_scores": val_scores,
            "opik_rescored_scores": rescored,
            "candidate_summary": candidate_rows,
            "best_candidate_iteration": candidate_rows[best_idx]["iteration"]
            if candidate_rows
            else 0,
            "selected_candidate_index": best_idx,
            "selected_candidate_gepa_score": val_scores[best_idx]
            if best_idx < len(val_scores)
            else None,
            "selected_candidate_opik_score": best_score,
            "gepa_live_metric_used": True,
            "gepa_live_metric_call_count": self._gepa_live_metric_calls,
            "selected_candidate_item_scores": per_item_scores,
            "dataset_item_ids": [item.get("id") for item in items],
        }
        if experiment_config:
            details["experiment"] = experiment_config

        final_messages = final_prompt.get_messages()

        if self.verbose >= 1:
            gepa_reporting.display_candidate_scores(
                candidate_rows, verbose=self.verbose
            )
            gepa_reporting.display_selected_candidate(
                best_prompt_text, best_score, verbose=self.verbose
            )

        if LOGGER.isEnabledFor(logging.DEBUG):
            for idx, row in enumerate(candidate_rows):
                LOGGER.debug(
                    "candidate=%s source=%s gepa=%s opik=%s",
                    idx,
                    row.get("source"),
                    row.get("gepa_score"),
                    row.get("opik_score"),
                )
            LOGGER.debug(
                "selected candidate idx=%s gepa=%s opik=%.4f",
                best_idx,
                details.get("selected_candidate_gepa_score"),
                best_score,
            )

        return OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=final_messages,
            score=best_score,
            metric_name=metric.__name__,
            optimization_id=opt_id,
            dataset_id=ds_id,
            initial_prompt=initial_prompt_messages,
            initial_score=initial_score,
            details=details,
            history=history,
            llm_calls=None,
        )

    # ------------------------------------------------------------------
    # Helpers used by BaseOptimizer.evaluate_prompt
    # ------------------------------------------------------------------

    def _extract_system_text(self, prompt: chat_prompt.ChatPrompt) -> str:
        messages = prompt.get_messages()
        for message in messages:
            if message.get("role") == "system":
                return str(message.get("content", "")).strip()
        for message in messages:
            if message.get("role") == "user":
                return f"You are a helpful assistant. Respond to: {message.get('content', '')}"
        return "You are a helpful assistant."

    def _extract_system_text_from_candidate(
        self, candidate: Dict[str, Any], fallback: str
    ) -> str:
        for key in ("system_prompt", "system", "prompt"):
            value = candidate.get(key)
            if isinstance(value, str) and value.strip():
                return value
        return fallback

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
        return score
