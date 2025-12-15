import logging
import sys
from typing import Any, Literal, overload
from collections.abc import Callable

import opik
from opik import Dataset, opik_context
from opik.evaluation import evaluator as opik_evaluator
from opik.evaluation.metrics.score_result import ScoreResult

from ...base_optimizer import BaseOptimizer
from ...reporting_utils import (
    display_configuration,
    convert_tqdm_to_rich,
    suppress_opik_logs,
)
from ...api_objects import chat_prompt
from ...optimization_result import OptimizationResult
from ...optimizable_agent import OptimizableAgent
from ...utils import (
    optimization_context,
    create_litellm_agent_class,
    disable_experiment_reporting,
    enable_experiment_reporting,
    unique_ordered_by_key,
)
from ...task_evaluator import _create_metric_class
from ... import task_evaluator, helpers
from . import reporting as gepa_reporting
from .adapter import OpikDataInst, OpikGEPAAdapter

logger = logging.getLogger(__name__)


class GepaOptimizer(BaseOptimizer):
    """
    The GEPA (Genetic-Pareto) Optimizer uses a genetic algorithm with Pareto optimization
    to improve prompts while balancing multiple objectives.

    This algorithm is well-suited for complex optimization tasks where you want to find
    prompts that balance trade-offs between different quality metrics.

    Args:
        model: LiteLLM model name for the optimization algorithm
        model_parameters: Optional dict of LiteLLM parameters for optimizer's internal LLM calls.
            Common params: temperature, max_tokens, max_completion_tokens, top_p.
            See: https://docs.litellm.ai/docs/completion/input
        n_threads: Number of parallel threads for evaluation
        verbose: Controls internal logging/progress bars (0=off, 1=on)
        seed: Random seed for reproducibility
    """

    def __init__(
        self,
        model: str = "gpt-4o",
        model_parameters: dict[str, Any] | None = None,
        n_threads: int = 6,
        verbose: int = 1,
        seed: int = 42,
        name: str | None = None,
    ) -> None:
        # Validate required parameters
        if model is None:
            raise ValueError("model parameter is required and cannot be None")
        if not isinstance(model, str):
            raise ValueError(f"model must be a string, got {type(model).__name__}")
        if not model.strip():
            raise ValueError("model cannot be empty or whitespace-only")

        # Validate optional parameters
        if not isinstance(verbose, int):
            raise ValueError(
                f"verbose must be an integer, got {type(verbose).__name__}"
            )
        if verbose < 0:
            raise ValueError("verbose must be non-negative")

        if not isinstance(seed, int):
            raise ValueError(f"seed must be an integer, got {type(seed).__name__}")

        super().__init__(
            model=model,
            verbose=verbose,
            seed=seed,
            model_parameters=model_parameters,
            name=name,
        )
        self.n_threads = n_threads
        self._gepa_live_metric_calls = 0
        self._adapter = None  # Will be set during optimization

        # FIXME: When we have an Opik adapter, map this into GEPA's LLM calls directly
        if model_parameters:
            logger.warning(
                "GEPAOptimizer does not surface LiteLLM `model_parameters` for every internal call "
                "(e.g., output style inference, prompt generation). "
                "Provide overrides on the prompt itself if you need precise control."
            )

    def get_optimizer_metadata(self) -> dict[str, Any]:
        return {
            "model": self.model,
            "n_threads": self.n_threads,
        }

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
        project_name: str = "Optimization",
        optimization_id: str | None = None,
        validation_dataset: Dataset | None = None,
        max_trials: int = 10,
        reflection_minibatch_size: int = 3,
        candidate_selection_strategy: str = "pareto",
        skip_perfect_score: bool = True,
        perfect_score: float = 1.0,
        use_merge: bool = False,
        max_merge_invocations: int = 5,
        run_dir: str | None = None,
        track_best_outputs: bool = False,
        display_progress_bar: bool = False,
        seed: int = 42,
        raise_on_exception: bool = True,
        *args: Any,
        **kwargs: Any,
    ) -> OptimizationResult:
        """
        Optimize a prompt using GEPA (Genetic-Pareto) algorithm.

        Args:
            prompt: The prompt to optimize
            dataset: Opik Dataset to optimize on
            metric: Metric function to evaluate on
            experiment_config: Optional configuration for the experiment
            max_trials: Maximum number of different prompts to test (default: 10)
            n_samples: Optional number of items to test in the dataset
            auto_continue: Whether to auto-continue optimization
            agent_class: Optional agent class to use
            reflection_minibatch_size: Size of reflection minibatches (default: 3)
            candidate_selection_strategy: Strategy for candidate selection (default: "pareto")
            skip_perfect_score: Skip candidates with perfect scores (default: True)
            perfect_score: Score considered perfect (default: 1.0)
            use_merge: Enable merge operations (default: False)
            max_merge_invocations: Maximum merge invocations (default: 5)
            run_dir: Directory for run outputs (default: None)
            track_best_outputs: Track best outputs during optimization (default: False)
            display_progress_bar: Display progress bar (default: False)
            seed: Random seed for reproducibility (default: 42)
            raise_on_exception: Raise exceptions instead of continuing (default: True)
            optimization_id: Optional ID for the Opik optimization run; when provided it
                must be a valid UUIDv7 string.
            validation_dataset: Optional validation dataset used for Pareto tracking. When provided,
                helps prevent overfitting by evaluating candidates on unseen data. Falls back to
                the training dataset when not provided.

        Returns:
            OptimizationResult: Result of the optimization
        """
        # Use base class validation and setup methods
        self._validate_optimization_inputs(prompt, dataset, metric)
        # Keep a single agent class for the entire optimization (baseline +
        # GEPA adapter + final evaluation) so we never downgrade to the default
        # when we use GEPAs own internal evaluator.
        self.agent_class = self._setup_agent_class(prompt, agent_class)

        prompt = prompt.copy()
        if prompt.model is None:
            prompt.model = self.model
        if not prompt.model_kwargs:
            prompt.model_kwargs = dict(self.model_parameters)

        seed_prompt_text = self._extract_system_text(prompt)
        input_key, output_key = self._infer_dataset_keys(dataset)

        train_items = dataset.get_items()
        if n_samples and 0 < n_samples < len(train_items):
            train_items = train_items[:n_samples]

        val_source = validation_dataset or dataset
        val_items = val_source.get_items()
        if n_samples and 0 < n_samples < len(val_items):
            val_items = val_items[:n_samples]

        # Calculate max_metric_calls from max_trials and effective samples
        effective_n_samples = len(train_items)
        max_metric_calls = max_trials * effective_n_samples
        budget_limited_trials = (
            max_metric_calls // effective_n_samples if effective_n_samples else 0
        )
        if reflection_minibatch_size > max_trials:
            logger.warning(
                "reflection_minibatch_size (%s) exceeds max_trials (%s); GEPA reflection will not run. "
                "Increase max_trials or lower the minibatch.",
                reflection_minibatch_size,
                max_trials,
            )
        elif (
            budget_limited_trials and reflection_minibatch_size > budget_limited_trials
        ):
            logger.warning(
                "reflection_minibatch_size (%s) exceeds the number of candidates allowed by the metric budget (%s). "
                "Consider increasing max_trials or n_samples.",
                reflection_minibatch_size,
                budget_limited_trials,
            )

        train_insts = self._build_data_insts(train_items, input_key, output_key)
        val_insts = self._build_data_insts(val_items, input_key, output_key)

        self._gepa_live_metric_calls = 0

        base_prompt = prompt.copy()

        # Set project name from parameter
        self.project_name = project_name

        opt_id: str | None = None
        ds_id: str | None = getattr(dataset, "id", None)

        opik_client = opik.Opik(project_name=self.project_name)

        disable_experiment_reporting()

        # Hold the optimization context open for the entire run (other optimizers already
        # behave like this). The original `with ...` block exited immediately, which
        # marked GEPA optimizations as completed before any work happened.
        optimization_cm = optimization_context(
            client=opik_client,
            dataset_name=dataset.name,
            objective_name=metric.__name__,
            name=self.name,
            metadata=self._build_optimization_config(),
            optimization_id=optimization_id,
        )
        optimization_cm_entered = False

        try:
            optimization = optimization_cm.__enter__()
            optimization_cm_entered = True
            try:
                opt_id = optimization.id if optimization is not None else None
                self.current_optimization_id = opt_id
            except Exception:
                opt_id = None
                self.current_optimization_id = None

            gepa_reporting.display_header(
                algorithm=self.__class__.__name__,
                optimization_id=opt_id,
                dataset_id=getattr(dataset, "id", None),
                verbose=self.verbose,
            )

            display_configuration(
                messages=prompt.get_messages(),
                optimizer_config={
                    "optimizer": self.__class__.__name__,
                    "model": self.model,
                    "max_trials": max_trials,
                    "n_samples": n_samples or "all",
                    "max_metric_calls": max_metric_calls,
                    "reflection_minibatch_size": reflection_minibatch_size,
                    "candidate_selection_strategy": candidate_selection_strategy,
                    "validation_dataset": getattr(val_source, "name", None),
                },
                verbose=self.verbose,
            )

            baseline_eval_result: Any | None = None
            # Baseline evaluation
            initial_prompt_messages = prompt.get_messages()
            initial_score = 0.0
            with gepa_reporting.baseline_evaluation(verbose=self.verbose) as baseline:
                try:
                    eval_kwargs = dict(
                        prompt=prompt,
                        dataset=dataset,
                        metric=metric,
                        n_samples=n_samples,
                        optimization_id=opt_id,
                        extra_metadata={"phase": "baseline"},
                        verbose=0,
                        return_result=True,
                    )
                    with suppress_opik_logs():
                        initial_score, baseline_eval_result = (
                            self._evaluate_prompt_logged(**eval_kwargs)
                        )
                    baseline.set_score(initial_score)
                except Exception:
                    logger.exception("Baseline evaluation failed")

            adapter_prompt = self._apply_system_text(base_prompt, seed_prompt_text)
            adapter_prompt.model = self.model
            # Filter out GEPA-specific parameters that shouldn't be passed to LLM
            filtered_model_kwargs = {
                k: v
                for k, v in self.model_parameters.items()
                if k not in ["num_prompts_per_round", "rounds"]
            }
            adapter_prompt.model_kwargs = filtered_model_kwargs

            adapter = OpikGEPAAdapter(
                base_prompt=adapter_prompt,
                optimizer=self,
                metric=metric,
                system_fallback=seed_prompt_text,
                dataset=dataset,
                experiment_config=experiment_config,
                validation_dataset=validation_dataset,
            )

            try:
                import gepa
            except Exception as exc:  # pragma: no cover
                raise ImportError("gepa package is required for GepaOptimizer") from exc

            # When using our Rich logger, disable GEPA's native progress bar to avoid conflicts
            use_gepa_progress_bar = display_progress_bar if self.verbose == 0 else False

            with gepa_reporting.start_gepa_optimization(
                verbose=self.verbose, max_trials=max_trials
            ) as reporter:
                # Create logger with progress bar support
                logger_instance = gepa_reporting.RichGEPAOptimizerLogger(
                    self,
                    verbose=self.verbose,
                    progress=reporter.progress,
                    task_id=reporter.task_id,
                    max_trials=max_trials,
                )

                kwargs_gepa: dict[str, Any] = {
                    "seed_candidate": {"system_prompt": seed_prompt_text},
                    "trainset": train_insts,
                    "valset": val_insts,
                    "adapter": adapter,
                    "task_lm": None,
                    "reflection_lm": self.model,
                    "candidate_selection_strategy": candidate_selection_strategy,
                    "skip_perfect_score": skip_perfect_score,
                    "reflection_minibatch_size": reflection_minibatch_size,
                    "perfect_score": perfect_score,
                    "use_merge": use_merge,
                    "max_merge_invocations": max_merge_invocations,
                    "max_metric_calls": max_metric_calls,
                    "run_dir": run_dir,
                    "track_best_outputs": track_best_outputs,
                    "display_progress_bar": use_gepa_progress_bar,
                    "seed": seed,
                    "raise_on_exception": raise_on_exception,
                    "logger": logger_instance,
                }

                # Always pass max_metric_calls so external GEPA respects our budget.
                kwargs_gepa["max_metric_calls"] = max_metric_calls

                gepa_result = gepa.optimize(**kwargs_gepa)

                try:
                    opt_id = optimization.id if optimization is not None else None
                except Exception:
                    opt_id = None

        finally:
            exc_type, exc_val, exc_tb = sys.exc_info()
            if optimization_cm_entered:
                # Manually closing the optimization context ensures its status is updated
                # exactly once (completed/cancelled) after the entire GEPA run finishes.
                # We capture the exception tuple so the context manager can surface failures
                # just like a regular `with` block. This is admittedly a temporary workaround
                # until we put GEPA behind a native Opik adapter that can manage its lifecycle
                # without manual enter/exit plumbing.
                optimization_cm.__exit__(exc_type, exc_val, exc_tb)
            enable_experiment_reporting()

        # ------------------------------------------------------------------
        # Rescoring & result assembly
        # ------------------------------------------------------------------

        candidates: list[dict[str, str]] = getattr(gepa_result, "candidates", []) or []
        val_scores: list[float] = list(getattr(gepa_result, "val_aggregate_scores", []))

        indexed_candidates: list[tuple[int, dict[str, str]]] = list(
            enumerate(candidates)
        )
        filtered_indexed_candidates = unique_ordered_by_key(
            indexed_candidates,
            key=lambda item: self._extract_system_text_from_candidate(
                item[1], seed_prompt_text
            ).strip(),
        )
        filtered_candidates: list[dict[str, str]] = [
            candidate for _, candidate in filtered_indexed_candidates
        ]
        filtered_val_scores: list[float | None] = [
            val_scores[idx] if idx < len(val_scores) else None
            for idx, _ in filtered_indexed_candidates
        ]

        rescored: list[float] = []
        candidate_rows: list[dict[str, Any]] = []
        history: list[dict[str, Any]] = []

        # Wrap rescoring to prevent OPIK messages and experiment link displays
        with suppress_opik_logs():
            with convert_tqdm_to_rich(verbose=0):
                for idx, (original_idx, candidate) in enumerate(
                    filtered_indexed_candidates
                ):
                    candidate_prompt = self._extract_system_text_from_candidate(
                        candidate, seed_prompt_text
                    )
                    prompt_variant = self._apply_system_text(prompt, candidate_prompt)
                    prompt_variant.model = self.model
                    # Filter out GEPA-specific parameters that shouldn't be passed to LLM
                    filtered_model_kwargs = {
                        k: v
                        for k, v in self.model_parameters.items()
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
                        # TODO(opik-gepa): This rescoring round-trips through Opik's evaluator for every GEPA
                        # candidate. Once the GEPA→Opik adapter can stream scores back natively, remove this
                        # redundant evaluation loop and reuse GEPA's own scoring trace instead.
                        score = float(self._evaluate_prompt_logged(**eval_kwargs))
                    except Exception:
                        logger.debug(
                            "Rescoring failed for candidate %s", idx, exc_info=True
                        )
                        score = 0.0

                    rescored.append(score)
                    candidate_rows.append(
                        {
                            "iteration": idx + 1,
                            "system_prompt": candidate_prompt,
                            "gepa_score": filtered_val_scores[idx],
                            "opik_score": score,
                            "source": self.__class__.__name__,
                        }
                    )
                    history.append(
                        {
                            "iteration": idx + 1,
                            "prompt_candidate": candidate_prompt,
                            "scores": [
                                {
                                    "metric_name": f"GEPA-{metric.__name__}",
                                    "score": filtered_val_scores[idx],
                                },
                                {"metric_name": metric.__name__, "score": score},
                            ],
                            "metadata": {},
                        }
                    )

        if rescored:

            def _tie_break(idx: int) -> tuple[float, float, int]:
                opik_score = rescored[idx]
                gepa_score = filtered_val_scores[idx]
                gepa_numeric = (
                    float(gepa_score)
                    if isinstance(gepa_score, (int, float))
                    else float("-inf")
                )
                return opik_score, gepa_numeric, idx

            best_idx = max(range(len(rescored)), key=_tie_break)
            best_score = rescored[best_idx]
        else:
            if filtered_indexed_candidates:
                gepa_best_idx = getattr(gepa_result, "best_idx", 0) or 0
                best_idx = next(
                    (
                        i
                        for i, (original_idx, _) in enumerate(
                            filtered_indexed_candidates
                        )
                        if original_idx == gepa_best_idx
                    ),
                    0,
                )
                if filtered_val_scores and 0 <= best_idx < len(filtered_val_scores):
                    score_value = filtered_val_scores[best_idx]
                    best_score = float(score_value) if score_value is not None else 0.0
                else:
                    best_score = float(initial_score)
            else:
                best_idx = 0
                best_score = float(initial_score)

        best_candidate = (
            filtered_candidates[best_idx]
            if filtered_candidates
            else {"system_prompt": seed_prompt_text}
        )
        best_prompt_text = self._extract_system_text_from_candidate(
            best_candidate, seed_prompt_text
        )
        best_matches_seed = best_prompt_text.strip() == seed_prompt_text.strip()

        final_prompt = self._apply_system_text(prompt, best_prompt_text)
        final_prompt.model = self.model
        # Filter out GEPA-specific parameters that shouldn't be passed to LLM
        filtered_model_kwargs = {
            k: v
            for k, v in self.model_parameters.items()
            if k not in ["num_prompts_per_round", "rounds"]
        }
        final_prompt.model_kwargs = filtered_model_kwargs

        final_eval_result: Any | None = None
        final_experiment_config: dict[str, Any] | None = None
        analysis_project_name: str | None = self.project_name

        reuse_baseline_eval = best_matches_seed and baseline_eval_result is not None

        if not reuse_baseline_eval:
            with suppress_opik_logs():
                try:
                    configuration_updates = helpers.drop_none(
                        {"gepa": {"phase": "final", "selected": True}}
                    )
                    final_experiment_config = self._prepare_experiment_config(
                        prompt=final_prompt,
                        dataset=dataset,
                        metric=metric,
                        experiment_config=experiment_config,
                        configuration_updates=configuration_updates,
                    )
                    analysis_project_name = (
                        final_experiment_config.get("project_name")
                        if final_experiment_config
                        else self.project_name
                    )
                    final_llm_agent = self._create_agent_for_prompt(
                        final_prompt, project_name=analysis_project_name
                    )

                    def final_llm_task(dataset_item: dict[str, Any]) -> dict[str, str]:
                        messages = final_prompt.get_messages(dataset_item)
                        raw = final_llm_agent.invoke(messages)
                        if self.current_optimization_id:
                            opik_context.update_current_trace(
                                tags=[self.current_optimization_id, "Evaluation"]
                            )
                        return {"llm_output": raw.strip()}

                    metric_class = _create_metric_class(metric)

                    if opt_id:
                        final_eval_result = opik_evaluator.evaluate_optimization_trial(
                            optimization_id=opt_id,
                            dataset=dataset,
                            task=final_llm_task,
                            project_name=final_experiment_config.get("project_name"),
                            dataset_item_ids=None,
                            scoring_metrics=[metric_class],
                            task_threads=self.n_threads,
                            nb_samples=n_samples,
                            experiment_config=final_experiment_config,
                            verbose=0,
                        )
                    else:
                        final_eval_result = opik_evaluator.evaluate(
                            dataset=dataset,
                            task=final_llm_task,
                            project_name=final_experiment_config.get("project_name"),
                            dataset_item_ids=None,
                            scoring_metrics=[metric_class],
                            task_threads=self.n_threads,
                            nb_samples=n_samples,
                            experiment_config=final_experiment_config,
                            verbose=0,
                        )
                except Exception:
                    logger.debug("Final evaluation failed", exc_info=True)
        else:
            final_eval_result = baseline_eval_result

        per_item_scores: list[dict[str, Any]] = []
        try:
            analysis_prompt = final_prompt.copy()
            agent = self._create_agent_for_prompt(
                analysis_prompt, project_name=analysis_project_name
            )
            for item in train_items:
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
            logger.debug("Per-item diagnostics failed", exc_info=True)

        trial_info: dict[str, Any] | None = None
        if final_eval_result is not None:
            experiment_name = getattr(final_eval_result, "experiment_name", None)
            experiment_url = getattr(final_eval_result, "experiment_url", None)
            trial_ids = []
            try:
                trial_ids = sorted(
                    {
                        str(test_result.trial_id)
                        for test_result in getattr(
                            final_eval_result, "test_results", []
                        )
                        if getattr(test_result, "trial_id", None) is not None
                    }
                )
            except Exception:
                logger.debug("Failed to extract trial IDs", exc_info=True)

            trial_info = {
                "experiment_name": experiment_name,
                "experiment_url": experiment_url,
                "trial_ids": trial_ids,
            }

        details: dict[str, Any] = {
            "model": self.model,
            "temperature": self.model_parameters.get("temperature"),
            "optimizer": self.__class__.__name__,
            "num_candidates": len(filtered_candidates),
            "total_metric_calls": getattr(gepa_result, "total_metric_calls", None),
            "parents": getattr(gepa_result, "parents", None),
            "val_scores": filtered_val_scores,
            "opik_rescored_scores": rescored,
            "candidate_summary": candidate_rows,
            "best_candidate_iteration": (
                candidate_rows[best_idx]["iteration"] if candidate_rows else 0
            ),
            "selected_candidate_index": best_idx if filtered_candidates else None,
            "selected_candidate_gepa_score": (
                filtered_val_scores[best_idx]
                if filtered_val_scores and 0 <= best_idx < len(filtered_val_scores)
                else None
            ),
            "selected_candidate_opik_score": best_score,
            "gepa_live_metric_used": True,
            "gepa_live_metric_call_count": self._gepa_live_metric_calls,
            "selected_candidate_item_scores": per_item_scores,
            "dataset_item_ids": [item.get("id") for item in train_items],
            "selected_candidate_trial_info": trial_info,
        }
        if reuse_baseline_eval:
            details["final_evaluation_reused_baseline"] = True
        if experiment_config:
            details["experiment"] = experiment_config

        final_messages = final_prompt.get_messages()

        if self.verbose >= 1:
            gepa_reporting.display_candidate_scores(
                candidate_rows, verbose=self.verbose
            )
            gepa_reporting.display_selected_candidate(
                best_prompt_text,
                best_score,
                verbose=self.verbose,
                trial_info=trial_info,
            )

        if logger.isEnabledFor(logging.DEBUG):
            for idx, row in enumerate(candidate_rows):
                logger.debug(
                    "candidate=%s source=%s gepa=%s opik=%s",
                    idx,
                    row.get("source"),
                    row.get("gepa_score"),
                    row.get("opik_score"),
                )
            logger.debug(
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

    def _create_agent_for_prompt(
        self,
        prompt_obj: chat_prompt.ChatPrompt,
        project_name: str | None = None,
    ) -> OptimizableAgent:
        # Late initialization only happens if GEPA is being used stand-alone.
        # NOTE: OptimizableAgent defaults to LiteLLM. We cache whichever agent class the
        # user provided so GEPA never downgrades to LiteLLM (which breaks tracing).
        if not hasattr(self, "agent_class") or self.agent_class is None:
            self.agent_class = create_litellm_agent_class(
                prompt_obj, optimizer_ref=self
            )
        instantiate_kwargs: dict[str, Any] = {}
        if project_name is not None:
            instantiate_kwargs["project_name"] = project_name

        try:
            return self._instantiate_agent(
                prompt_obj, agent_class=self.agent_class, **instantiate_kwargs
            )
        except TypeError:
            return self._instantiate_agent(prompt_obj, agent_class=self.agent_class)

    def _build_optimization_config(self) -> dict[str, Any]:
        return self._build_optimization_metadata()

    @overload
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
        return_result: Literal[True] = True,
    ) -> tuple[float, Any | None]: ...

    @overload
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
        return_result: Literal[False] = False,
    ) -> float: ...

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
        return_result: bool = False,
    ) -> float | tuple[float, Any | None]:
        """
        Run an evaluation (baseline/rescoring/final) and optionally capture the full
        Opik EvaluationResult when callers need trace/test metadata (GEPA baseline).
        FIXME(opik-gepa): Once GEPA exposes a clean scoring API, this helper should be
        replaced with a dedicated adapter rather than invoking task_evaluator directly.
        """
        if prompt.model is None:
            prompt.model = self.model
        if prompt.model_kwargs is None:
            prompt.model_kwargs = self.model_parameters

        agent = self._create_agent_for_prompt(prompt)

        def llm_task(dataset_item: dict[str, Any]) -> dict[str, str]:
            messages = prompt.get_messages(dataset_item)
            raw = agent.invoke(messages)

            # Add tags to trace for optimization tracking
            if self.current_optimization_id:
                opik_context.update_current_trace(
                    tags=[self.current_optimization_id, "Evaluation"]
                )

            return {"llm_output": raw.strip()}

        configuration_updates = helpers.drop_none({"gepa": extra_metadata})
        experiment_config = self._prepare_experiment_config(
            prompt=prompt,
            dataset=dataset,
            metric=metric,
            experiment_config=experiment_config,
            configuration_updates=configuration_updates,
        )

        if return_result:
            score, eval_result = task_evaluator.evaluate_with_result(
                dataset=dataset,
                dataset_item_ids=dataset_item_ids,
                metric=metric,
                evaluated_task=llm_task,
                num_threads=self.n_threads,
                project_name=experiment_config.get("project_name"),
                experiment_config=experiment_config,
                optimization_id=optimization_id,
                n_samples=n_samples,
                verbose=verbose,
            )
            return score, eval_result

        score = task_evaluator.evaluate(
            dataset=dataset,
            dataset_item_ids=dataset_item_ids,
            metric=metric,
            evaluated_task=llm_task,
            num_threads=self.n_threads,
            project_name=experiment_config.get("project_name"),
            experiment_config=experiment_config,
            optimization_id=optimization_id,
            n_samples=n_samples,
            verbose=verbose,
        )
        return score
