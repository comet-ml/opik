import logging
import sys
from typing import Any

import opik
from opik import Dataset

from ...base_optimizer import BaseOptimizer
from ...reporting_utils import (
    display_configuration,
    convert_tqdm_to_rich,
    suppress_opik_logs,
)
from ...api_objects import chat_prompt
from ...api_objects.types import rebuild_content_with_new_text, MetricFunction
from ...optimization_result import OptimizationResult
from ...agents import OptimizableAgent, LiteLLMAgent
from ...utils import (
    optimization_context,
    unique_ordered_by_key,
)
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

    def optimize_prompt(  # type: ignore[override]
        self,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: MetricFunction,
        agent: OptimizableAgent | None = None,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
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
            prompt: The prompt(s) to optimize. Can be a single ChatPrompt or a dict of ChatPrompts.
                All message types (system, user, assistant) will be optimized.
            dataset: Opik Dataset to optimize on
            metric: Metric function to evaluate on
            agent: Optional agent instance to use for evaluation. If None, uses LiteLLMAgent.
            experiment_config: Optional configuration for the experiment
            max_trials: Maximum number of different prompts to test (default: 10)
            n_samples: Optional number of items to test in the dataset
            auto_continue: Whether to auto-continue optimization
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
        # Use base class validation
        self._validate_optimization_inputs(
            prompt, dataset, metric, support_content_parts=True
        )

        # Create default agent if None
        if agent is None:
            agent = LiteLLMAgent(project_name=project_name)
        self.agent = agent

        # Normalize prompt input: convert single prompt to dict
        optimizable_prompts: dict[str, chat_prompt.ChatPrompt]
        if isinstance(prompt, chat_prompt.ChatPrompt):
            optimizable_prompts = {prompt.name: prompt}
            is_single_prompt_optimization = True
        else:
            optimizable_prompts = prompt
            is_single_prompt_optimization = False

        # Work with a copy of prompts to avoid mutating the original
        optimizable_prompts = {
            name: p.copy() for name, p in optimizable_prompts.items()
        }

        # Set model defaults on all prompts
        for p in optimizable_prompts.values():
            if p.model is None:
                p.model = self.model
            if not p.model_kwargs:
                p.model_kwargs = dict(self.model_parameters)

        # Build multi-component seed_candidate from all messages in all prompts
        seed_candidate: dict[str, str] = {}
        for prompt_name, prompt_obj in optimizable_prompts.items():
            messages = prompt_obj.get_messages()
            for idx, msg in enumerate(messages):
                component_key = f"{prompt_name}_{msg['role']}_{idx}"
                content = msg.get("content", "")
                # Handle content that might be a list (multimodal)
                if isinstance(content, list):
                    # Extract text from content parts
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

        # Calculate max_metric_calls from max_trials and effective samples
        effective_n_samples = len(train_items)
        max_metric_calls = max_trials * effective_n_samples
        budget_limited_trials = (
            max_metric_calls // effective_n_samples if effective_n_samples else 0
        )
        if reflection_minibatch_size > max_trials:
            # TODO(opik_optimizer/#testing): consider clamping the minibatch size inside the
            # optimizer so that small-test configs don't surface warnings (currently individual
            # tests override the parameter to keep CI quiet).
            logger.warning(
                "reflection_minibatch_size (%s) exceeds max_trials (%s); GEPA reflection will not run. "
                "Increase max_trials or lower the minibatch.",
                reflection_minibatch_size,
                max_trials,
            )
        elif (
            budget_limited_trials and reflection_minibatch_size > budget_limited_trials
        ):
            # TODO(opik_optimizer/#testing): same as above; auto-adjusting based on the effective
            # metric budget would avoid manual overrides in smoke tests.
            logger.warning(
                "reflection_minibatch_size (%s) exceeds the number of candidates allowed by the metric budget (%s). "
                "Consider increasing max_trials or n_samples.",
                reflection_minibatch_size,
                budget_limited_trials,
            )

        train_insts = self._build_data_insts(train_items, input_key, output_key)
        val_insts = self._build_data_insts(val_items, input_key, output_key)

        self._gepa_live_metric_calls = 0

        # Set project name from parameter
        self.project_name = project_name

        opt_id: str | None = None
        ds_id: str | None = getattr(dataset, "id", None)

        opik_client = opik.Opik(project_name=self.project_name)

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
                messages=optimizable_prompts,
                optimizer_config={
                    "optimizer": self.__class__.__name__,
                    "model": self.model,
                    "max_trials": max_trials,
                    "n_samples": n_samples or "all",
                    "max_metric_calls": max_metric_calls,
                    "reflection_minibatch_size": reflection_minibatch_size,
                    "candidate_selection_strategy": candidate_selection_strategy,
                    "validation_dataset": getattr(val_source, "name", None),
                    "num_prompts": len(optimizable_prompts),
                    "num_components": len(seed_candidate),
                },
                verbose=self.verbose,
            )

            # Store initial prompts for result
            initial_prompts = {
                name: p.copy() for name, p in optimizable_prompts.items()
            }
            initial_score = 0.0
            with gepa_reporting.baseline_evaluation(verbose=self.verbose) as baseline:
                try:
                    # For baseline evaluation, use the base class evaluate_prompt
                    # which handles dict prompts via agent.invoke_agent
                    initial_score = self.evaluate_prompt(
                        prompt=optimizable_prompts,
                        dataset=dataset,
                        metric=metric,
                        agent=self.agent,
                        n_samples=n_samples,
                        verbose=0,
                    )
                    baseline.set_score(initial_score)
                except Exception:
                    logger.exception("Baseline evaluation failed")

            # Create the adapter with multi-prompt support
            adapter = OpikGEPAAdapter(
                base_prompts=optimizable_prompts,
                agent=self.agent,
                optimizer=self,
                metric=metric,
                dataset=dataset,
                experiment_config=experiment_config,
            )

            try:
                import gepa
                import inspect
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
                    "seed_candidate": seed_candidate,
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

                optimize_sig = None
                try:
                    optimize_sig = inspect.signature(gepa.optimize)
                except Exception:
                    optimize_sig = None

                if optimize_sig and "stop_callbacks" not in optimize_sig.parameters:
                    kwargs_gepa["max_metric_calls"] = max_metric_calls

                gepa_result: Any = gepa.optimize(**kwargs_gepa)

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

        # ------------------------------------------------------------------
        # Rescoring & result assembly
        # ------------------------------------------------------------------

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

        # Get summary text for display
        best_prompt_text = self._get_candidate_summary_text(
            best_candidate, optimizable_prompts
        )

        details: dict[str, Any] = {
            "model": self.model,
            "temperature": self.model_parameters.get("temperature"),
            "optimizer": self.__class__.__name__,
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
            "gepa_live_metric_used": True,
            "gepa_live_metric_call_count": self._gepa_live_metric_calls,
            "dataset_item_ids": [item.get("id") for item in train_items],
        }
        if best_matches_seed:
            details["final_evaluation_reused_baseline"] = True
        if experiment_config:
            details["experiment"] = experiment_config

        if self.verbose >= 1:
            gepa_reporting.display_candidate_scores(
                candidate_rows, verbose=self.verbose
            )
            gepa_reporting.display_selected_candidate(
                best_prompt_text,
                best_score,
                verbose=self.verbose,
                trial_info=None,
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

        # Convert result format based on input type
        result_prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt]
        result_initial_prompt: (
            chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt]
        )
        if is_single_prompt_optimization:
            result_prompt = list(final_prompts.values())[0]
            result_initial_prompt = list(initial_prompts.values())[0]
        else:
            result_prompt = final_prompts
            result_initial_prompt = initial_prompts

        return OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=result_prompt,
            score=best_score,
            metric_name=metric.__name__,
            optimization_id=opt_id,
            dataset_id=ds_id,
            initial_prompt=result_initial_prompt,
            initial_score=initial_score,
            details=details,
            history=history,
            llm_calls=None,
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
