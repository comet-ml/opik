import logging
from typing import Any, cast

from opik import Dataset

from ...base_optimizer import AlgorithmResult, BaseOptimizer, OptimizationContext
from ...reporting_utils import (
    convert_tqdm_to_rich,
    suppress_opik_logs,
)
from ...api_objects import chat_prompt
from ...api_objects.types import rebuild_content_with_new_text
from ...utils import (
    unique_ordered_by_key,
)
from ...utils.prompt_library import PromptOverrides
from . import reporting as gepa_reporting
from . import prompts as gepa_prompts
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
        prompt_overrides: Accepted for API parity, but ignored (GEPA does not expose prompt hooks).
    """

    DEFAULT_PROMPTS = gepa_prompts.DEFAULT_PROMPTS

    def __init__(
        self,
        model: str = "gpt-4o",
        model_parameters: dict[str, Any] | None = None,
        n_threads: int = 6,
        verbose: int = 1,
        seed: int = 42,
        name: str | None = None,
        skip_perfect_score: bool = True,
        perfect_score: float = 0.95,
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

    def pre_optimization(self, context: OptimizationContext) -> None:
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
        experiment_config = context.experiment_config

        reflection_minibatch_size = context.extra_params.get(
            "reflection_minibatch_size", 3
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

        for p in optimizable_prompts.values():
            if p.model is None:
                p.model = self.model
            if not p.model_kwargs:
                p.model_kwargs = dict(self.model_parameters)

        seed_candidate: dict[str, str] = {}
        for prompt_name, prompt_obj in optimizable_prompts.items():
            messages = prompt_obj.get_messages()
            for idx, msg in enumerate(messages):
                component_key = f"{prompt_name}_{msg['role']}_{idx}"
                content = msg.get("content", "")
                if isinstance(content, list):
                    text_parts = [
                        part.get("text", "")
                        for part in content
                        if isinstance(part, dict) and part.get("type") == "text"
                    ]
                    content = " ".join(text_parts)
                seed_candidate[component_key] = str(content)

        input_key, output_key = self._infer_dataset_keys(dataset)

        train_items = dataset.get_items()
        if n_samples and 0 < n_samples < len(train_items):
            train_items = train_items[:n_samples]

        val_source = validation_dataset or dataset
        val_items = val_source.get_items()
        if n_samples and 0 < n_samples < len(val_items):
            val_items = val_items[:n_samples]

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

        train_insts = self._build_data_insts(train_items, input_key, output_key)
        val_insts = self._build_data_insts(val_items, input_key, output_key)

        self._adapter_metric_calls = 0

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
                task_id=reporter.task_id,
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

            kwargs_gepa["max_metric_calls"] = max_metric_calls

            gepa_result: Any = gepa.optimize(**kwargs_gepa)

        candidates: list[dict[str, str]] = getattr(gepa_result, "candidates", []) or []
        val_scores: list[float] = list(getattr(gepa_result, "val_aggregate_scores", []))

        # Filter duplicate candidates based on content
        indexed_candidates: list[tuple[int, dict[str, str]]] = list(
            enumerate(candidates)
        )
        filtered_indexed_candidates = unique_ordered_by_key(
            indexed_candidates,
            key=lambda item: str(sorted(item[1].items())),
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
                    # Rebuild prompts from candidate
                    prompt_variants = self._rebuild_prompts_from_candidate(
                        optimizable_prompts, candidate
                    )

                    try:
                        # Use base class evaluate_prompt which handles dict prompts
                        score = self.evaluate_prompt(
                            prompt=prompt_variants,
                            dataset=dataset,
                            metric=metric,
                            agent=self.agent,
                            n_samples=n_samples,
                            verbose=0,
                        )
                        score = float(score)
                    except Exception:
                        logger.debug(
                            "Rescoring failed for candidate %s", idx, exc_info=True
                        )
                        score = 0.0

                    rescored.append(score)
                    # Get a summary text for display (backward compatible)
                    candidate_summary_text = self._get_candidate_summary_text(
                        candidate, optimizable_prompts
                    )
                    candidate_rows.append(
                        {
                            "iteration": idx + 1,
                            "system_prompt": candidate_summary_text,
                            "gepa_score": filtered_val_scores[idx],
                            "opik_score": score,
                            "source": self.__class__.__name__,
                            "components": {
                                k: v
                                for k, v in candidate.items()
                                if not k.startswith("_") and k not in ("source", "id")
                            },
                        }
                    )
                    history.append(
                        {
                            "iteration": idx + 1,
                            "prompt_candidate": candidate,
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

        # Get best candidate and rebuild final prompts
        best_candidate = (
            filtered_candidates[best_idx] if filtered_candidates else seed_candidate
        )
        final_prompts = self._rebuild_prompts_from_candidate(
            optimizable_prompts, best_candidate
        )

        # Check if best matches initial seed
        best_matches_seed = best_candidate == seed_candidate

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
                "selected candidate idx=%s opik=%.4f",
                best_idx,
                best_score,
            )

        # finish_reason, stopped_early, stop_reason are handled by base class

        # Build metadata for the result
        metadata: dict[str, Any] = {
            "num_candidates": len(filtered_candidates),
            "num_components": len(seed_candidate),
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
            "adapter_metric_used": True,
            "adapter_metric_call_count": self._adapter_metric_calls,
            "dataset_item_ids": [item.get("id") for item in train_items],
        }
        if best_matches_seed:
            metadata["final_evaluation_reused_baseline"] = True
        if experiment_config:
            metadata["experiment"] = experiment_config

        return AlgorithmResult(
            best_prompts=final_prompts,
            best_score=best_score,
            history=history,
            metadata=metadata,
        )

    # ------------------------------------------------------------------
    # Helpers for multi-prompt optimization
    # ------------------------------------------------------------------

    def _rebuild_prompts_from_candidate(
        self,
        base_prompts: dict[str, chat_prompt.ChatPrompt],
        candidate: dict[str, str],
    ) -> dict[str, chat_prompt.ChatPrompt]:
        """Rebuild prompts with optimized messages from a GEPA candidate.

        Args:
            base_prompts: Dict of original prompts to use as templates.
            candidate: Dict mapping component keys (e.g., "prompt_name_role_idx") to optimized content.

        Returns:
            Dict of ChatPrompt objects with optimized message content,
            preserving tools, function_map, model, model_kwargs, and multimodal parts.
        """
        rebuilt: dict[str, chat_prompt.ChatPrompt] = {}
        for prompt_name, prompt_obj in base_prompts.items():
            original_messages = prompt_obj.get_messages()
            new_messages = []
            for idx, msg in enumerate(original_messages):
                component_key = f"{prompt_name}_{msg['role']}_{idx}"
                original_content = msg.get("content", "")
                optimized_text = candidate.get(component_key)

                if optimized_text is not None:
                    # Use helper to preserve image parts while updating text
                    new_content = rebuild_content_with_new_text(
                        original_content, optimized_text
                    )
                else:
                    new_content = original_content

                new_messages.append({"role": msg["role"], "content": new_content})

            # prompt.copy() preserves tools, function_map, model, model_kwargs
            new_prompt = prompt_obj.copy()
            new_prompt.set_messages(new_messages)
            rebuilt[prompt_name] = new_prompt
        return rebuilt

    def _get_candidate_summary_text(
        self,
        candidate: dict[str, str],
        base_prompts: dict[str, chat_prompt.ChatPrompt],
    ) -> str:
        """Get a summary text representation of a candidate for display."""
        # Try to get system prompt content first for backward-compatible display
        for prompt_name in base_prompts:
            system_key = f"{prompt_name}_system_0"
            if system_key in candidate:
                return candidate[system_key][:200]
        # Fall back to first component
        for key, value in candidate.items():
            if not key.startswith("_") and key not in ("source", "id"):
                return str(value)[:200]
        return "<no content>"

    def _build_optimization_config(self) -> dict[str, Any]:
        return self._build_optimization_metadata()
