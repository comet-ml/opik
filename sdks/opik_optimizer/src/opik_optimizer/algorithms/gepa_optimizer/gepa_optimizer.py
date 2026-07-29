import logging
import math
from collections.abc import Callable
from typing import Any, cast

from gepa.utils.stop_condition import NoImprovementStopper, ScoreThresholdStopper

from ...base_optimizer import BaseOptimizer
from ... import constants
from ...core import llm_calls as _llm_calls
from ...core.state import (
    AlgorithmResult,
    FinishReason,
    OptimizationContext,
    build_optimization_metadata,
)
from ...utils.prompt_library import PromptOverrides
from . import helpers, reporting as gepa_reporting
from . import prompts as gepa_prompts
from .adapter import OpikGEPAAdapter
from .ops import candidate_ops, result_ops, scoring_ops

logger = logging.getLogger(__name__)


# Each reflection iteration scores the current and the proposed candidate on
# the same mini-batch (gepa/proposer/reflective_mutation), so a batch of b
# costs ~2*b metric calls per iteration. Warn when fewer than this many
# iterations fit the budget — the run degenerates into a few mutation shots.
MIN_EXPECTED_REFLECTION_ITERATIONS = 5


def _warn_if_reflection_minibatch_exhausts_budget(
    *,
    reflection_minibatch_size: int,
    max_metric_calls: int,
) -> None:
    """Warn when the mini-batch leaves too few reflection iterations in budget.

    A large mini-batch never stops GEPA reflection from running — the gepa
    engine does not gate iterations on the remaining budget — it just makes
    each iteration cost ~2*reflection_minibatch_size metric calls, so the
    metric-call budget stops the run after roughly
    ``max_metric_calls // (2 * reflection_minibatch_size)`` iterations.
    """
    estimated_iterations = max_metric_calls // (2 * reflection_minibatch_size)
    if estimated_iterations < MIN_EXPECTED_REFLECTION_ITERATIONS:
        # TODO(opik_optimizer/#gepa-batching): Centralize reflection minibatch budgeting with the python-backend's resolve_reflection_minibatch_size.
        logger.warning(
            "reflection_minibatch_size=%s costs ~%s metric calls per reflection "
            "iteration; the metric-call budget (%s) allows only ~%s iteration(s) "
            "before GEPA stops. Lower reflection_minibatch_size or raise "
            "max_trials/n_samples.",
            reflection_minibatch_size,
            2 * reflection_minibatch_size,
            max_metric_calls,
            estimated_iterations,
        )


def _coerce_positive_int(
    value: Any, *, default: int, allow_zero: bool, name: str
) -> int:
    """Coerce a user-supplied extra_params value to a valid int at the boundary.

    These are best-effort config knobs (typed Any), so this never raises: an
    absent value (None) uses ``default``; an un-parseable value falls back to
    ``default``; a non-integer float is floored with a warning (never silently);
    a value below the minimum uses 0 when ``allow_zero`` (i.e. "disabled") else
    ``default``. Invalid inputs are logged by type only — the raw value is not
    dumped, so a stray dict/list can't flood WARNING logs.
    """
    if value is None:
        return default
    try:
        coerced = int(value)
    except (TypeError, ValueError, OverflowError):
        # OverflowError covers float('inf'); ValueError covers NaN / bad strings.
        logger.warning(
            "Ignoring invalid %s (got %s); using default %s.",
            name,
            type(value).__name__,
            default,
        )
        return default
    if isinstance(value, float) and not value.is_integer():
        logger.warning(
            "%s=%s is not an integer; rounding down to %s.", name, value, coerced
        )
    minimum = 0 if allow_zero else 1
    if coerced < minimum:
        fallback = 0 if allow_zero else default
        logger.warning(
            "%s=%s is below the minimum (%s); using %s.",
            name,
            coerced,
            minimum,
            fallback,
        )
        return fallback
    return coerced


def _coerce_max_reflection_calls(value: Any, *, max_trials: int) -> int:
    """Validate the max_reflection_calls override to a non-negative int.

    Defaults to ``max_trials``: with GEPA's default round-robin component
    selector each proposal iteration makes at most one reflection-LM call, and
    the metric budget admits exactly ``max_trials`` candidates — so a healthy
    run never needs more reflections than trials. ``0`` disables the cap.
    """
    return _coerce_positive_int(
        value,
        default=max_trials,
        allow_zero=True,
        name="max_reflection_calls",
    )


class _ReflectionBudgetStopper:
    """Stop callback that halts GEPA once the reflection-LM call budget is spent.

    Reflection calls are made by the proposer *inside* an iteration, while stop
    callbacks run at the top of the engine loop — so the cap is enforced with at
    most one iteration of overshoot (one reflection call per component under the
    default round-robin selector).
    """

    def __init__(self, optimizer: "GepaOptimizer", max_reflection_calls: int) -> None:
        self._optimizer = optimizer
        self.max_reflection_calls = max_reflection_calls

    def __call__(self, _gepa_state: Any) -> bool:
        return self._optimizer._reflection_call_count >= self.max_reflection_calls


def _coerce_no_improvement_iterations(value: Any) -> int:
    """Validate the no_improvement_iterations override to a non-negative int.

    ``0`` disables the stall stopper; ``None`` (or an absent/invalid value) uses
    the default. See _coerce_positive_int for the boundary handling.
    """
    return _coerce_positive_int(
        value,
        default=constants.DEFAULT_GEPA_NO_IMPROVEMENT_ITERATIONS,
        allow_zero=True,
        name="no_improvement_iterations",
    )


def _candidate_full_eval_scores(val_scores: Any) -> list[float]:
    """Comparable full-eval scores of gepa's *candidates*, seed excluded.

    Index 0 of gepa's full-eval list is the seed program's own eval
    (gepa/core/state.py seeds the list with it); everything after it is a
    candidate. Non-numeric and non-finite entries are dropped so an inf from a
    custom metric cannot read as "threshold reached".
    """
    return [
        float(score)
        for score in list(val_scores or [])[1:]
        if isinstance(score, (int, float))
        and not isinstance(score, bool)
        and math.isfinite(score)
    ]


class CandidateScoreThresholdStopper(ScoreThresholdStopper):
    """Stop when a *candidate* full eval reaches the threshold — never the seed.

    The vendored stopper reads the whole score list, so it can end a run on the
    seed program's own eval, before any candidate exists. Scores are not
    reproducible in general (a model may fix its own temperature and stay
    sampled), so on a coarse metric that turns "stop because the target was
    reached" into a coin flip — and the run is then reported as a perfect score
    while showing no improvement (OPIK-7511).

    "The baseline is already good enough" is owned by ``should_skip_optimization``
    on Opik's own baseline evaluation, which runs before GEPA — so ignoring the
    seed here loses no stop path, it just stops the two from overlapping.
    """

    def __call__(self, gepa_state: Any) -> bool:
        try:
            scores = getattr(gepa_state, "program_full_scores_val_set", None)
            return any(
                score >= self.threshold for score in _candidate_full_eval_scores(scores)
            )
        except Exception:
            # Never take a run down from a stop callback — but do not fail quiet
            # either: swallowing this silently disables threshold stopping, and a
            # gepa upgrade that renames the score list would otherwise look like
            # "the target was simply never reached".
            logger.warning(
                "Could not read gepa full-eval scores (%s); threshold stopping is "
                "inactive for this iteration.",
                type(gepa_state).__name__,
                exc_info=True,
            )
            return False


def _build_gepa_stop_callbacks(
    perfect_score: float, no_improvement_iterations: Any
) -> tuple[list[Any], Any]:
    """Build GEPA stop callbacks and return (stop_callbacks, no_improvement_stopper).

    Both stoppers read full-eval (valset) scores only, keeping the stop decision
    apples-to-apples with mini-batch screening excluded:
    - CandidateScoreThresholdStopper stops once a *candidate* full eval reaches
      perfect_score (only wired for a positive target — see below);
    - NoImprovementStopper ends the reject/skip spin below the threshold
      (disabled when no_improvement_iterations coerces to 0).

    gepa always falls back to the max_metric_calls budget, so an empty list here
    is safe.
    """
    iterations = _coerce_no_improvement_iterations(no_improvement_iterations)
    stop_callbacks: list[Any] = []
    # A non-positive perfect_score would make the threshold stopper fire on the
    # very first candidate full eval (any score >= 0), halting the run
    # immediately — so only wire it for a sensible target.
    if perfect_score > 0:
        stop_callbacks.append(CandidateScoreThresholdStopper(perfect_score))
    no_improvement_stopper: Any = None
    if iterations:
        no_improvement_stopper = NoImprovementStopper(iterations)
        stop_callbacks.append(no_improvement_stopper)
    return stop_callbacks, no_improvement_stopper


_GEPA_VERSION_REQUIREMENT = (
    "opik-optimizer requires gepa>=0.1.0 (the <curr_param>/<side_info> "
    "reflection-template contract; gepa 0.0.x speaks the older "
    "<curr_instructions>/<inputs_outputs_feedback> dialect)."
)


def _validate_reflection_prompt_template(template: str) -> None:
    """Raise if the reflection template is missing a marker gepa requires.

    Checked with gepa's own validator so the rule cannot drift from upstream.
    Called from __init__ so a bad override fails when the optimizer is
    constructed — before the baseline evaluation spends real LLM calls — rather
    than at gepa.optimize() hand-off, which happens after it.

    A rejection is attributed to whoever caused it: a malformed override to the
    caller (ValueError), an install that cannot honour our template contract to
    the environment (RuntimeError naming the version floor).
    """
    if not isinstance(template, str):
        raise ValueError(
            "Invalid reflection_prompt_template override: expected a string, got "
            f"{type(template).__name__}. The template must contain both the "
            "<curr_param> and <side_info> markers."
        )

    # gepa does not document a public validation entry point, so this reaches
    # into its instruction-proposal strategy; the except below turns a moved or
    # renamed symbol after a gepa bump into an explicit version error instead of
    # an opaque ImportError at construction.
    try:
        from gepa.strategies.instruction_proposal import InstructionProposalSignature

        validate = InstructionProposalSignature.validate_prompt_template
    except (ImportError, AttributeError) as exc:
        raise RuntimeError(
            "The installed gepa version does not expose the reflection-template "
            f"validator this optimizer relies on. {_GEPA_VERSION_REQUIREMENT}"
        ) from exc

    # The branch above only catches gepa <=0.0.17, which has no validator at all.
    # 0.0.18-0.0.27 do expose one — checking the *old* markers — so control would
    # reach validate() and reject our own built-in default, and the handler below
    # would blame an override the caller never passed. Read gepa's own default to
    # tell the dialect apart. Only a readable str counts as evidence: a future
    # gepa that renames or reshapes the attribute must not be misreported as too
    # old (the built-in-default check below still covers that case).
    installed_default = getattr(
        InstructionProposalSignature, "default_prompt_template", None
    )
    if isinstance(installed_default, str) and "<curr_param>" not in installed_default:
        raise RuntimeError(
            "The installed gepa version uses an older reflection-template "
            f"dialect. {_GEPA_VERSION_REQUIREMENT}"
        )

    try:
        validate(template)
    except ValueError as exc:
        if template == gepa_prompts.REFLECTION_PROMPT_TEMPLATE:
            # No override involved — the caller only asked for the optimizer. If
            # gepa rejects the template we ship, the install is the problem.
            raise RuntimeError(
                "The installed gepa version rejects this optimizer's built-in "
                f"reflection template ({exc}). {_GEPA_VERSION_REQUIREMENT}"
            ) from exc
        raise ValueError(
            f"Invalid reflection_prompt_template override: {exc}. The template "
            "must contain both the <curr_param> and <side_info> markers."
        ) from exc


def _resolve_gepa_finish_reason(
    *,
    val_scores: list[float],
    perfect_score: float,
    no_improvement_stopper: Any,
    no_improvement_iterations: Any,
    total_metric_calls: Any,
    max_metric_calls: int,
    stop_file_watched: bool,
    reflection_calls: int = 0,
    max_reflection_calls: int = 0,
) -> FinishReason | None:
    """Return why GEPA's search ended, or None to leave the label to the caller.

    Our own stops ("perfect_score"/"no_improvement") are decided from candidate
    full-eval (valset) scores, exactly like the stop conditions — same seed
    exclusion and same non-finite filtering, so the label can never claim a stop
    the stopper did not make. "reflection_budget" is decided from the
    reflection-LM call counter behind _ReflectionBudgetStopper, and is checked
    before the stop-file branch below: exhausting the reflection budget also
    ends the run with metric-call budget still on the clock, so the unspent
    budget alone cannot tell the two apart.

    One other exit is distinguishable, and only when gepa can actually produce
    it: with ``run_dir`` set, gepa wires a FileStopper on ``<run_dir>/gepa.stop``
    (gepa/api.py), which ends the run with budget still on the clock. Detecting
    it by the unspent budget rests on ``GEPAResult.total_metric_calls`` being the
    same counter ``MaxMetricCallsStopper`` compares (true in gepa 0.1.x, but the
    dependency has no upper bound), so it is gated on ``stop_file_watched``: with
    no stop file to watch there is nothing to detect, and a future counter drift
    would otherwise relabel every ordinary budget exit "cancelled". Callers that
    never set ``run_dir`` — Optimization Studio among them — keep the plain
    "max_trials" fallback.
    """
    candidate_scores = _candidate_full_eval_scores(val_scores)
    if (
        perfect_score > 0
        and candidate_scores
        and max(candidate_scores) >= perfect_score
    ):
        logger.info(
            "GEPA stopped early: full-eval score %.4f reached perfect_score %.4f.",
            max(candidate_scores),
            perfect_score,
        )
        return "perfect_score"
    if (
        no_improvement_stopper is not None
        and no_improvement_stopper.iterations_without_improvement
        >= no_improvement_stopper.max_iterations_without_improvement
    ):
        logger.info(
            "GEPA stopped early: no full-eval improvement for %s iterations.",
            no_improvement_iterations,
        )
        return "no_improvement"
    if max_reflection_calls > 0 and reflection_calls >= max_reflection_calls:
        logger.info(
            "GEPA stopped early: reflection-LM call budget exhausted (%s/%s).",
            reflection_calls,
            max_reflection_calls,
        )
        return "reflection_budget"
    if (
        stop_file_watched
        and isinstance(total_metric_calls, int)
        and not isinstance(total_metric_calls, bool)
        and total_metric_calls < max_metric_calls
    ):
        logger.info(
            "GEPA stopped after %s of %s metric calls with neither wired stopper "
            "fired; treating it as an external stop request (gepa.stop in "
            "run_dir).",
            total_metric_calls,
            max_metric_calls,
        )
        return "cancelled"
    return None


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
        prompt_overrides: Optional dict or callable overriding the optimizer's own
            prompts. The one supported key is "reflection_prompt_template", passed
            through to gepa.optimize() as the instruction-proposal prompt; it must
            contain the <curr_param> and <side_info> markers.
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
            prompt_overrides=prompt_overrides,
        )
        self.n_threads = n_threads
        self._adapter_metric_calls = 0
        self._reflection_call_count = 0
        self._max_reflection_calls = 0
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

        # Fail here, not at the gepa.optimize() hand-off: that happens after the
        # baseline evaluation, so a malformed override would otherwise cost a
        # full dataset scoring pass before raising.
        _validate_reflection_prompt_template(
            self.prompts.get("reflection_prompt_template")
        )

    def _resolve_reflection_prompt_template(self) -> str:
        """Return the reflection template to hand gepa.optimize(), validated.

        __init__ already validated the configured template, so this re-check only
        catches a template swapped in afterwards via ``optimizer.prompts.set()``.
        GEPA would also silently ignore the template if the adapter defined
        propose_new_texts — OpikGEPAAdapter deliberately does not.
        """
        template = self.prompts.get("reflection_prompt_template")
        _validate_reflection_prompt_template(template)
        return template

    def get_optimizer_metadata(self) -> dict[str, Any]:
        return {
            "model": self.model,
            "n_threads": self.n_threads,
        }

    def _make_reflection_lm(
        self, context: OptimizationContext
    ) -> Callable[[str | list[dict[str, str]]], str]:
        """Build the reflection-LM callable handed to gepa.optimize().

        Passing a callable (instead of the bare model name) routes GEPA's
        reflection calls through core.llm_calls.call_model, so they are traced
        as Opik spans (opik_call_type="reflection", cost visible per call),
        honor the optimizer's model_parameters, and are counted — both into the
        run-wide llm_call_counter and into _reflection_call_count, which
        _ReflectionBudgetStopper reads to enforce max_reflection_calls.
        """
        metadata = _llm_calls.build_llm_call_metadata(self, "reflection")

        def _reflection_lm(prompt: str | list[dict[str, str]]) -> str:
            messages = (
                [{"role": "user", "content": prompt}]
                if isinstance(prompt, str)
                else list(prompt)
            )
            # Count before calling: a failed attempt still spent tokens.
            self._reflection_call_count += 1
            return cast(
                str,
                _llm_calls.call_model(
                    messages=messages,
                    model=self.model,
                    model_parameters=self.model_parameters,
                    metadata=metadata,
                    optimization_id=context.optimization_id,
                    project_name=context.project_name,
                ),
            )

        return _reflection_lm

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

        # Coerce at the boundary: extra_params is user-supplied (Any), and this
        # value is both used in _warn_if_reflection_minibatch_exhausts_budget
        # and passed to gepa.optimize — a stray string must not crash setup.
        reflection_minibatch_size = _coerce_positive_int(
            context.extra_params.get("reflection_minibatch_size"),
            default=context.n_samples_minibatch or 3,
            allow_zero=False,
            name="reflection_minibatch_size",
        )
        candidate_selection_strategy = context.extra_params.get(
            "candidate_selection_strategy", "pareto"
        )
        use_merge = context.extra_params.get("use_merge", False)
        max_merge_invocations = context.extra_params.get("max_merge_invocations", 5)
        no_improvement_iterations = context.extra_params.get(
            "no_improvement_iterations",
            constants.DEFAULT_GEPA_NO_IMPROVEMENT_ITERATIONS,
        )
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

        all_train_items = dataset.get_items()
        all_val_items = val_source.get_items()
        # Derive the guard's keys from every column the datasets can supply,
        # not just the rows this run samples: rescoring evaluates against the
        # full dataset, so a column carried only by an unsampled row would
        # otherwise go unprotected. Done here, before narrowing, so the full
        # lists can be released immediately — only the small key set outlives
        # this block, and holding every item through optimization would undo
        # the memory benefit of sampling.
        known_placeholder_keys = candidate_ops.dataset_placeholder_keys(
            (*all_train_items, *all_val_items)
        )
        train_items = _apply_plan(all_train_items, train_plan)
        val_items = _apply_plan(all_val_items, val_plan)
        del all_train_items, all_val_items

        effective_n_samples = len(train_items)
        max_metric_calls = max_trials * effective_n_samples
        _warn_if_reflection_minibatch_exhausts_budget(
            reflection_minibatch_size=reflection_minibatch_size,
            max_metric_calls=max_metric_calls,
        )

        # Reflection-LM calls are not metric calls, so max_metric_calls does not
        # bound them — they get their own ceiling (OPIK-7521). 0 disables the cap.
        max_reflection_calls = _coerce_max_reflection_calls(
            context.extra_params.get("max_reflection_calls"),
            max_trials=max_trials,
        )
        self._max_reflection_calls = max_reflection_calls

        train_insts = helpers.build_data_insts(train_items, input_key, output_key)
        val_insts = helpers.build_data_insts(val_items, input_key, output_key)

        self._adapter_metric_calls = 0
        self._reflection_call_count = 0

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
            gepa_val_item_ids={
                str(item["id"]) for item in val_items if item.get("id") is not None
            },
        )

        try:
            import gepa
        except Exception as exc:  # pragma: no cover
            raise ImportError("gepa package is required for GepaOptimizer") from exc

        # gepa.optimize() only stops on its metric-call budget by default, so a
        # run that hits 100% on a full eval would keep burning budget. Wire in
        # full-eval-only stoppers (see _build_gepa_stop_callbacks).
        stop_callbacks, no_improvement_stopper = _build_gepa_stop_callbacks(
            self.perfect_score, no_improvement_iterations
        )
        if max_reflection_calls > 0:
            stop_callbacks.append(_ReflectionBudgetStopper(self, max_reflection_calls))

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
                "reflection_lm": self._make_reflection_lm(context),
                "candidate_selection_strategy": candidate_selection_strategy,
                # Replaces GEPA's default instruction-proposal prompt, which
                # instructs the reflection LM to inline example content and so
                # invites it to overwrite the user's template variables.
                "reflection_prompt_template": self._resolve_reflection_prompt_template(),
                "skip_perfect_score": self.skip_perfect_score,
                "reflection_minibatch_size": reflection_minibatch_size,
                "perfect_score": self.perfect_score,
                "use_merge": use_merge,
                "max_merge_invocations": max_merge_invocations,
                "max_metric_calls": max_metric_calls,
                "stop_callbacks": stop_callbacks,
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

        # Surface why the search ended so the run doesn't silently look like a
        # full budget burn. finish_reason flows into result metadata/logs via
        # the base class (runtime.build_final_result). The gepa engine only
        # exits via its stop callbacks, so when neither of ours fired and the
        # metric-call budget (max_metric_calls = max_trials * n_samples) is
        # spent, that is "max_trials", not "completed": GEPA's trials_completed
        # only counts Opik-side evaluate() calls, so the base-class fallback
        # would otherwise mislabel every budget-exhausted run. Budget left on
        # the clock means someone else stopped the run, but only a run with a
        # stop file to watch can be stopped that way — see the resolver.
        gepa_finish_reason = _resolve_gepa_finish_reason(
            val_scores=val_scores,
            perfect_score=self.perfect_score,
            no_improvement_stopper=no_improvement_stopper,
            no_improvement_iterations=no_improvement_iterations,
            total_metric_calls=getattr(gepa_result, "total_metric_calls", None),
            max_metric_calls=max_metric_calls,
            stop_file_watched=run_dir is not None,
            reflection_calls=self._reflection_call_count,
            max_reflection_calls=max_reflection_calls,
        )
        context.finish_reason = (
            context.finish_reason or gepa_finish_reason or "max_trials"
        )
        logger.info(
            "GEPA made %s reflection-LM call(s) (budget: %s).",
            self._reflection_call_count,
            max_reflection_calls or "unlimited",
        )

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
            known_placeholder_keys=known_placeholder_keys,
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
            known_placeholder_keys=known_placeholder_keys,
        )

    def _build_optimization_config(self) -> dict[str, Any]:
        return build_optimization_metadata(self)
