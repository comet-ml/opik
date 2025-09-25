import logging
from contextlib import nullcontext
from typing import Any, ContextManager
from collections.abc import Callable

import opik
from opik import Dataset
from opik.evaluation.metrics.score_result import ScoreResult

from ..base_optimizer import BaseOptimizer
from ..optimization_config import chat_prompt, mappers
from ..optimization_result import OptimizationResult
from ..optimizable_agent import OptimizableAgent
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
        project_name: str | None = None,
        reflection_model: str | None = None,
        verbose: int = 1,
        seed: int = 42,
        **model_kwargs: Any,
    ) -> None:
        # Validate required parameters
        if model is None:
            raise ValueError("model parameter is required and cannot be None")
        if not isinstance(model, str):
            raise ValueError(f"model must be a string, got {type(model).__name__}")
        if not model.strip():
            raise ValueError("model cannot be empty or whitespace-only")

        # Validate optional parameters
        if project_name is not None and not isinstance(project_name, str):
            raise ValueError(
                f"project_name must be a string or None, got {type(project_name).__name__}"
            )

        if reflection_model is not None and not isinstance(reflection_model, str):
            raise ValueError(
                f"reflection_model must be a string or None, got {type(reflection_model).__name__}"
            )

        if not isinstance(verbose, int):
            raise ValueError(
                f"verbose must be an integer, got {type(verbose).__name__}"
            )
        if verbose < 0:
            raise ValueError("verbose must be non-negative")

        if not isinstance(seed, int):
            raise ValueError(f"seed must be an integer, got {type(seed).__name__}")

        super().__init__(model=model, verbose=verbose, seed=seed, **model_kwargs)
        self.project_name = project_name
        self.reflection_model = reflection_model or model
        self.num_threads = self.model_kwargs.pop("num_threads", 6)
        self._gepa_live_metric_calls = 0
        self._adapter = None  # Will be set during optimization

    def cleanup(self) -> None:
        """
        Clean up GEPA-specific resources.
        """
        # Call parent cleanup
        super().cleanup()
        
        # Clear GEPA-specific resources
        self._adapter = None
        self._gepa_live_metric_calls = 0
        
        logger.debug("Cleaned up GEPA-specific resources")

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _build_data_insts(
        self,
        dataset_items: list[dict[str, Any]],
        input_key: str,
        output_key: str,
    ) -> list[OpikDataInst]:
        data_insts: list[OpikDataInst] = []
        for item in dataset_items:
            additional_context: dict[str, str] = {}
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

    def _infer_dataset_keys(self, dataset: Dataset) -> tuple[str, str]:
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
        dataset: Dataset,
        metric: Callable,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
        agent_class: type[OptimizableAgent] | None = None,
        **kwargs: Any,
    ) -> OptimizationResult:
        """
        Optimize a prompt using GEPA (Genetic-Pareto) algorithm.

        Args:
            prompt: The prompt to optimize
            dataset: Opik Dataset to optimize on
            metric: Metric function to evaluate on
            experiment_config: Optional configuration for the experiment
            n_samples: Optional number of items to test in the dataset
            auto_continue: Whether to auto-continue optimization
            agent_class: Optional agent class to use
            **kwargs: GEPA-specific parameters:
                max_metric_calls (int | None): Maximum number of metric evaluations (default: 30)
                reflection_minibatch_size (int): Size of reflection minibatches (default: 3)
                candidate_selection_strategy (str): Strategy for candidate selection (default: "pareto")
                skip_perfect_score (bool): Skip candidates with perfect scores (default: True)
                perfect_score (float): Score considered perfect (default: 1.0)
                use_merge (bool): Enable merge operations (default: False)
                max_merge_invocations (int): Maximum merge invocations (default: 5)
                run_dir (str | None): Directory for run outputs (default: None)
                track_best_outputs (bool): Track best outputs during optimization (default: False)
                display_progress_bar (bool): Display progress bar (default: False)
                seed (int): Random seed for reproducibility (default: 42)
                raise_on_exception (bool): Raise exceptions instead of continuing (default: True)
                mcp_config (MCPExecutionConfig | None): MCP tool calling configuration (default: None)

        Returns:
            OptimizationResult: Result of the optimization
        """
        # Use base class validation and setup methods
        self.validate_optimization_inputs(prompt, dataset, metric)
        

        # Extract GEPA-specific parameters from kwargs
        max_metric_calls: int | None = kwargs.get("max_metric_calls", 30)
        reflection_minibatch_size: int = int(kwargs.get("reflection_minibatch_size", 3))
        candidate_selection_strategy: str = str(
            kwargs.get("candidate_selection_strategy", "pareto")
        )
        skip_perfect_score: bool = kwargs.get("skip_perfect_score", True)
        perfect_score: float = float(kwargs.get("perfect_score", 1.0))
        use_merge: bool = kwargs.get("use_merge", False)
        max_merge_invocations: int = int(kwargs.get("max_merge_invocations", 5))
        run_dir: str | None = kwargs.get("run_dir", None)
        track_best_outputs: bool = kwargs.get("track_best_outputs", False)
        display_progress_bar: bool = kwargs.get("display_progress_bar", False)
        seed: int = int(kwargs.get("seed", 42))
        raise_on_exception: bool = kwargs.get("raise_on_exception", True)
        kwargs.pop("mcp_config", None)  # Added for MCP support (for future use)

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

        opt_id: str | None = None
        ds_id: str | None = getattr(dataset, "id", None)

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
            # Filter out GEPA-specific parameters that shouldn't be passed to LLM
            filtered_model_kwargs = {
                k: v
                for k, v in self.model_kwargs.items()
                if k not in ["num_prompts_per_round", "rounds"]
            }
            adapter_prompt.model_kwargs = filtered_model_kwargs

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

            kwargs_gepa: dict[str, Any] = {
                "seed_candidate": {"system_prompt": seed_prompt_text},
                "trainset": data_insts,
                "valset": data_insts,
                "adapter": adapter,
                "task_lm": None,
                "reflection_lm": self.reflection_model,
                "candidate_selection_strategy": candidate_selection_strategy,
                "skip_perfect_score": skip_perfect_score,
                "reflection_minibatch_size": reflection_minibatch_size,
                "perfect_score": perfect_score,
                "use_merge": use_merge,
                "max_merge_invocations": max_merge_invocations,
                "max_metric_calls": max_metric_calls,
                "run_dir": run_dir,
                "track_best_outputs": track_best_outputs,
                "display_progress_bar": display_progress_bar,
                "seed": seed,
                "raise_on_exception": raise_on_exception,
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

        candidates: list[dict[str, str]] = getattr(gepa_result, "candidates", []) or []
        val_scores: list[float] = list(getattr(gepa_result, "val_aggregate_scores", []))

        rescored: list[float] = []
        candidate_rows: list[dict[str, Any]] = []
        history: list[dict[str, Any]] = []

        for idx, candidate in enumerate(candidates):
            candidate_prompt = self._extract_system_text_from_candidate(
                candidate, seed_prompt_text
            )
            prompt_variant = self._apply_system_text(prompt, candidate_prompt)
            prompt_variant.project_name = self.project_name
            prompt_variant.model = self.model
            # Filter out GEPA-specific parameters that shouldn't be passed to LLM
            filtered_model_kwargs = {
                k: v
                for k, v in self.model_kwargs.items()
                if k not in ["num_prompts_per_round", "rounds"]
            }
            prompt_variant.model_kwargs = filtered_model_kwargs

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
        # Filter out GEPA-specific parameters that shouldn't be passed to LLM
        filtered_model_kwargs = {
            k: v
            for k, v in self.model_kwargs.items()
            if k not in ["num_prompts_per_round", "rounds"]
        }
        final_prompt.model_kwargs = filtered_model_kwargs

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

        per_item_scores: list[dict[str, Any]] = []
        try:
            analysis_prompt = final_prompt.copy()
            agent_cls = create_litellm_agent_class(analysis_prompt, optimizer=self)
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

        details: dict[str, Any] = {
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
        self, candidate: dict[str, Any], fallback: str
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
        metric: Callable[[dict[str, Any], str], ScoreResult],
        n_samples: int | None = None,
        dataset_item_ids: list[str] | None = None,
        experiment_config: dict[str, Any] | None = None,
        optimization_id: str | None = None,
        extra_metadata: dict[str, Any] | None = None,
        verbose: int = 1,
    ) -> float:
        if prompt.model is None:
            prompt.model = self.model
        if prompt.model_kwargs is None:
            prompt.model_kwargs = self.model_kwargs

        agent_class = create_litellm_agent_class(prompt, optimizer=self)
        agent = agent_class(prompt)

        def llm_task(dataset_item: dict[str, Any]) -> dict[str, str]:
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
