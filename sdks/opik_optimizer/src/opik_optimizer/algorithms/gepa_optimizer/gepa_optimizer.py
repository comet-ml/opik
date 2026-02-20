import logging
from typing import Any, cast


from ...base_optimizer import BaseOptimizer
from ... import constants
from ...core.state import (
    AlgorithmResult,
    OptimizationContext,
    build_optimization_metadata,
)
from ...utils.prompt_library import PromptOverrides
from . import helpers, reporting as gepa_reporting
from . import prompts as gepa_prompts
from .adapter import OpikGEPAAdapter
from .ops import candidate_ops, result_ops, scoring_ops

logger = logging.getLogger(__name__)


class GepaOptimizer(BaseOptimizer):
    # FIXME(opik_optimizer/#gepa-tool-optimization): Re-enable when GEPA adapter
    # can mutate and score tool descriptions end-to-end.
    supports_tool_optimization: bool = False
    supports_prompt_optimization: bool = True
    supports_multimodal: bool = True
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
        prompt_overrides: Accepted for API parity, but ignored (GEPA does not expose prompt hooks).
    """

    DEFAULT_PROMPTS = gepa_prompts.DEFAULT_PROMPTS

    def __init__(
        self,
        model: str = constants.DEFAULT_MODEL,
        model_parameters: dict[str, Any] | None = None,
        n_threads: int = constants.DEFAULT_NUM_THREADS,
        verbose: int = 1,
        seed: int = constants.DEFAULT_SEED,
        name: str | None = None,
        skip_perfect_score: bool = constants.DEFAULT_SKIP_PERFECT_SCORE,
        perfect_score: float = constants.DEFAULT_PERFECT_SCORE,
        prompt_overrides: PromptOverrides = None,
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
            skip_perfect_score=skip_perfect_score,
            perfect_score=perfect_score,
            prompt_overrides=None,
        )
        self.n_threads = n_threads
        self._adapter_metric_calls = 0
        self._adapter = None  # Will be set during optimization
        self._validation_dataset = None
        self._gepa_rescored_scores: list[float] = []
        self._gepa_filtered_val_scores: list[float | None] = []

        # FIXME: When we have an Opik adapter, map this into GEPA's LLM calls directly
        if model_parameters:
            logger.warning(
                "GEPAOptimizer does not surface LiteLLM `model_parameters` for every internal call "
                "(e.g., output style inference, prompt generation). "
                "Provide overrides on the prompt itself if you need precise control."
            )
        if prompt_overrides is not None:
            logger.warning(
                "GEPA prompt overrides are not supported yet and will be ignored."
            )

    def get_optimizer_metadata(self) -> dict[str, Any]:
        return {
            "model": self.model,
            "n_threads": self.n_threads,
        }

    def pre_optimize(self, context: OptimizationContext) -> None:
        """Set up GEPA-specific state before optimization."""
        # Store agent reference for use in adapter
        self.agent = context.agent

        # Allow skip_perfect_score and perfect_score to be overridden per-call
        skip_perfect_score = context.extra_params.get(
            "skip_perfect_score", self.skip_perfect_score
        )
        perfect_score = context.extra_params.get("perfect_score", self.perfect_score)
        self.skip_perfect_score = skip_perfect_score
        self.perfect_score = perfect_score

    def get_config(self, context: OptimizationContext) -> dict[str, Any]:
        """Return optimizer-specific configuration for display."""
        return {
            "optimizer": self.__class__.__name__,
            "model": self.model,
            "max_trials": context.max_trials,
            "n_samples": context.n_samples or "all",
        }

    def get_metadata(self, context: OptimizationContext) -> dict[str, Any]:
        """
        Return GEPA-specific metadata for the optimization result.

        Provides algorithm-specific configuration. Trial counts come from context.
        """
        return {
            "optimizer": self.__class__.__name__,
            "max_trials": context.max_trials,
            "n_samples": context.n_samples or "all",
        }

    def run_optimization(self, context: OptimizationContext) -> AlgorithmResult:
        """
        Run the GEPA optimization algorithm.

        Uses the external GEPA library for genetic-Pareto optimization. The algorithm:
        1. Builds data instances from dataset
        2. Runs GEPA's genetic optimization with the adapter
        3. Rescores candidates using Opik's evaluation
        4. Returns the best candidate

        Args:
            context: The optimization context with prompts, dataset, metric, etc.

        Returns:
            AlgorithmResult with best prompts, score, history, and metadata.
        """
        # Initialize progress tracking for display
        self._current_round = 0
        self._total_rounds = context.max_trials

        optimizable_prompts = context.prompts
        initial_score = cast(float, context.baseline_score)
        n_samples = context.n_samples
        max_trials = context.max_trials
        dataset = context.dataset
        metric = context.metric
        validation_dataset = context.validation_dataset
        self._validation_dataset = validation_dataset
        experiment_config = context.experiment_config

        reflection_minibatch_size = context.extra_params.get(
            "reflection_minibatch_size",
            context.n_samples_minibatch or 3,
        )
        candidate_selection_strategy = context.extra_params.get(
            "candidate_selection_strategy", "pareto"
        )
        use_merge = context.extra_params.get("use_merge", False)
        max_merge_invocations = context.extra_params.get("max_merge_invocations", 5)
        run_dir = context.extra_params.get("run_dir", None)
        track_best_outputs = context.extra_params.get("track_best_outputs", False)
        display_progress_bar = context.extra_params.get("display_progress_bar", False)
        seed = context.extra_params.get("seed", 42)
        raise_on_exception = context.extra_params.get("raise_on_exception", True)
        optimizable_roles = (
            context.extra_params.get("optimizable_roles")
            if context.extra_params
            else None
        )
        if optimizable_roles is not None and "user" in optimizable_roles:
            logger.warning(
                "Opik Optimizer with GEPA currently uses a non-native adapter; optimizing user messages may drop candidate edits when constraints apply."
            )
        if optimizable_roles is not None and "user" not in optimizable_roles:
            logger.warning(
                "GEPA will drop candidate edits for disallowed roles due to optimize_prompt constraints."
            )

        for p in optimizable_prompts.values():
            if p.model is None:
                p.model = self.model
            if not p.model_kwargs:
                p.model_kwargs = dict(self.model_parameters)

        seed_candidate = candidate_ops.build_seed_candidate(
            optimizable_prompts=optimizable_prompts,
            allowed_roles=optimizable_roles,
            tool_names=context.extra_params.get("tool_names"),
            enable_tools=bool(context.extra_params.get("optimize_tools")),
        )

        input_key, output_key = helpers.infer_dataset_keys(dataset)

        train_plan = self._prepare_sampling_plan(
            dataset=dataset,
            n_samples=n_samples,
            phase="train",
            seed_override=seed,
            strategy=context.n_samples_strategy,
        )

        val_source = validation_dataset or dataset
        val_plan = self._prepare_sampling_plan(
            dataset=val_source,
            n_samples=n_samples,
            phase="val",
            seed_override=seed,
            strategy=context.n_samples_strategy,
        )

        def _apply_plan(items: list[dict[str, Any]], plan: Any) -> list[dict[str, Any]]:
            if not items:
                return items
            if plan.dataset_item_ids:
                id_set = set(plan.dataset_item_ids)
                return [item for item in items if item.get("id") in id_set]
            if plan.nb_samples is not None and plan.nb_samples < len(items):
                return items[: plan.nb_samples]
            return items

        train_items = _apply_plan(dataset.get_items(), train_plan)
        val_items = _apply_plan(val_source.get_items(), val_plan)

        effective_n_samples = len(train_items)
        max_metric_calls = max_trials * effective_n_samples
        budget_limited_trials = (
            max_metric_calls // effective_n_samples if effective_n_samples else 0
        )
        if reflection_minibatch_size > max_trials:
            # TODO(opik_optimizer/#gepa-batching): Centralize reflection minibatch clamping when we consolidate trial budgeting.
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

        train_insts = helpers.build_data_insts(train_items, input_key, output_key)
        val_insts = helpers.build_data_insts(val_items, input_key, output_key)

        self._adapter_metric_calls = 0

        if self.agent is None:
            raise ValueError("GepaOptimizer requires an agent to run evaluations.")

        adapter = OpikGEPAAdapter(
            base_prompts=optimizable_prompts,
            agent=self.agent,
            optimizer=self,
            context=context,
            metric=metric,
            dataset=dataset,
            experiment_config=experiment_config,
            validation_dataset=validation_dataset,
        )

        try:
            import gepa
        except Exception as exc:  # pragma: no cover
            raise ImportError("gepa package is required for GepaOptimizer") from exc

        use_adapter_progress_bar = display_progress_bar if self.verbose == 0 else False

        with gepa_reporting.start_gepa_optimization(
            verbose=self.verbose, max_trials=max_trials
        ) as reporter:
            logger_instance = gepa_reporting.RichGEPAOptimizerLogger(
                self,
                verbose=self.verbose,
                progress=reporter.progress,
                max_trials=max_trials,
            )

            kwargs_gepa: dict[str, Any] = {
                "seed_candidate": seed_candidate,
                "trainset": train_insts,
                "valset": val_insts,
                "adapter": adapter,
                "task_lm": None,
                "reflection_lm": self.model,
                "candidate_selection_strategy": candidate_selection_strategy,
                "skip_perfect_score": self.skip_perfect_score,
                "reflection_minibatch_size": reflection_minibatch_size,
                "perfect_score": self.perfect_score,
                "use_merge": use_merge,
                "max_merge_invocations": max_merge_invocations,
                "max_metric_calls": max_metric_calls,
                "run_dir": run_dir,
                "track_best_outputs": track_best_outputs,
                "display_progress_bar": use_adapter_progress_bar,
                "seed": seed,
                "raise_on_exception": raise_on_exception,
                "logger": logger_instance,
            }

            gepa_result: Any = gepa.optimize(**kwargs_gepa)

        candidates: list[dict[str, str]] = getattr(gepa_result, "candidates", []) or []
        val_scores: list[float] = list(getattr(gepa_result, "val_aggregate_scores", []))

        # Filter duplicate candidates based on content
        (
            filtered_candidates,
            filtered_val_scores,
            filtered_indexed_candidates,
        ) = candidate_ops.filter_duplicate_candidates(
            candidates=candidates,
            val_scores=val_scores,
        )

        rescored = scoring_ops.rescore_candidates(
            optimizer=self,
            context=context,
            dataset=context.evaluation_dataset,
            optimizable_prompts=optimizable_prompts,
            filtered_indexed_candidates=filtered_indexed_candidates,
            filtered_val_scores=filtered_val_scores,
            selection_policy=candidate_selection_strategy,
        )

        best_idx, best_score = candidate_ops.select_best_candidate_index(
            rescored=rescored,
            filtered_val_scores=filtered_val_scores,
            filtered_indexed_candidates=filtered_indexed_candidates,
            initial_score=float(initial_score),
            gepa_result=gepa_result,
        )
        best_candidate = (
            filtered_candidates[best_idx]
            if filtered_candidates and 0 <= best_idx < len(filtered_candidates)
            else seed_candidate
        )

        # Check if best matches initial seed
        best_matches_seed = best_candidate == seed_candidate

        if logger.isEnabledFor(logging.DEBUG):
            selected_label = best_idx if best_idx >= 0 else "baseline"
            logger.debug(
                "selected candidate idx=%s opik=%.4f",
                selected_label,
                best_score,
            )

        # finish_reason, stopped_early, stop_reason are handled by base class

        return result_ops.build_algorithm_result(
            optimizer=self,
            best_idx=best_idx,
            best_score=best_score,
            best_candidate=best_candidate,
            filtered_candidates=filtered_candidates,
            filtered_val_scores=filtered_val_scores,
            rescored=rescored,
            candidate_selection_strategy=candidate_selection_strategy,
            best_matches_seed=best_matches_seed,
            seed_candidate=seed_candidate,
            optimizable_prompts=optimizable_prompts,
            train_items=train_items,
            gepa_result=gepa_result,
            experiment_config=experiment_config,
        )

    def _build_optimization_config(self) -> dict[str, Any]:
        return build_optimization_metadata(self)
