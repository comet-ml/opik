"""Configuration for Optimization Studio."""

import math
import os
from typing import Optional

# Opik Configuration
OPIK_URL = os.getenv("OPIK_URL_OVERRIDE")

# Gateway base URL for LLM calls — points at the Opik backend's
# OpenAI-compatible completions endpoint so that all provider/key
# resolution happens in the Java backend (same path as the playground).
# rstrip("/") guards against deployments that set OPIK_URL_OVERRIDE with a
# trailing slash, which would otherwise produce a double-slash URL.
OPIK_GATEWAY_BASE_URL = f"{OPIK_URL.rstrip('/')}/v1/private" if OPIK_URL else None

# Metric Defaults
DEFAULT_REFERENCE_KEY = "answer"
DEFAULT_CASE_SENSITIVE = False

# Execution timeout for optimization jobs (default: 6 hours). The backend
# stalled-run reaper's runningTimeout MUST stay above this (config.yml, default 8h).
OPTIMIZATION_TIMEOUT_SECS = int(os.getenv("OPTSTUDIO_EXECUTION_TIMEOUT", "21600"))

# Dataset sampling (limits items used during optimization to prevent OOM)
DATASET_SAMPLES = int(os.getenv("OPTSTUDIO_DATASET_SAMPLES", "1000"))

# Optimization Runtime Parameters
# These are passed to optimizer.optimize_prompt() for all optimizer types.
# GEPA's reflection_minibatch_size is NOT pinned here — it scales with the
# dataset per run (see resolve_reflection_minibatch_size below).
OPTIMIZER_RUNTIME_PARAMS = {
    # Generic parameters (all optimizers)
    "max_trials": int(os.getenv("OPTIMIZER_MAX_TRIALS", "10")),
    "n_samples": DATASET_SAMPLES,

    # GEPA-specific parameters (ignored by other optimizers)
    "candidate_selection_strategy": os.getenv("OPTIMIZER_GEPA_CANDIDATE_SELECTION", "pareto"),  # "pareto" or "best"

    # Hierarchical-specific parameters (ignored by other optimizers)
    "max_retries": int(os.getenv("OPTIMIZER_HIERARCHICAL_MAX_RETRIES", "2")),
}

def _read_float_env(name: str, default: str, *, minimum: float, maximum: float) -> float:
    """Parse a float env var, failing fast at import on a malformed value.

    ``float()`` alone accepts "nan" and "inf", which silently break the score
    comparisons these values feed (``baseline >= nan`` is always False, ``>= inf``
    never true), so the range is enforced here instead of surfacing as a run that
    mysteriously never stops. Same fail-at-startup contract as
    _read_reflection_minibatch_override.
    """
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        raw = default
    stripped = raw.strip()
    try:
        value = float(stripped)
    except ValueError:
        # Truncate: the value is free text from the environment.
        raise ValueError(
            f"{name} must be a number between {minimum} and {maximum}, "
            f"got {stripped[:32]!r}"
        ) from None
    if not math.isfinite(value) or not (minimum <= value <= maximum):
        raise ValueError(
            f"{name} must be a finite number between {minimum} and {maximum}, "
            f"got {value}"
        )
    return value


# Studio-run perfect_score (OPIK-7511). perfect_score does double duty in the
# SDK: it is the baseline/iteration skip threshold AND (for GEPA) the run-level
# stop threshold via ScoreThresholdStopper. The SDK default (0.95) makes a
# strong-but-imperfect baseline end a Studio run immediately with zero
# candidates, presenting as "nothing improved". For Studio runs it must mean
# "nothing left to optimize", so pin it to 1.0 here (the gepa package's own
# default) rather than changing the SDK-wide default under every SDK user.
# Injected as a constructor default by OptimizerFactory.build, so it takes
# effect on the currently pinned opik_optimizer release — no pin bump needed.
# Range 0.0-1.0: every metric the Studio exposes is normalised to that (equals
# and json_schema_validator are 0/1, levenshtein_ratio/geval/numerical_similarity
# are ratios). A custom `code` metric on another scale should set perfect_score
# per run via optimizer_params rather than moving this deployment-wide default.
# 0.0 is legal and disables threshold stopping (see _build_gepa_stop_callbacks).
OPTIMIZER_PERFECT_SCORE = _read_float_env(
    "OPTIMIZER_PERFECT_SCORE", "1.0", minimum=0.0, maximum=1.0
)

# Temperature for the *task* model — the one whose completions are scored
# (OPIK-7511). At the provider default, repeating the SAME evaluation of the SAME
# prompt over the SAME dataset gave 0.90-1.00 (measured: gpt-4o-mini flipped even
# on unambiguous items; gpt-5-nano flipped 3 of 6 repeats), so a run's score —
# and every threshold decision taken on it — was partly a coin flip. Pinning it
# made the same eval reproduce exactly (6/6 identical). Not applied to the
# optimizer/reflection model, which needs sampling diversity to propose varied
# candidates. Models that fix their temperature (the gpt-5 family accepts only
# 1) ignore this via litellm's drop_params rather than failing the run.
OPTIMIZER_TASK_TEMPERATURE = _read_float_env(
    "OPTIMIZER_TASK_TEMPERATURE", "0.0", minimum=0.0, maximum=2.0
)

# GEPA reflection mini-batch sizing (OPIK-7511).
# GEPA only promotes a candidate to a full evaluation on a STRICT win over the
# reflection mini-batch (gepa/core/engine.py "new_sum <= old_sum: skip"). With
# the Studio's coarse 0/1 metrics (Equals, Levenshtein) a batch of b items can
# only take b+1 distinct sums, so a fixed b=5 discards most iterations for lack
# of resolution. Scale the batch with the dataset instead:
#
#   min(dataset_size,
#       max(GEPA_REFLECTION_MINIBATCH_MIN,
#           ceil(dataset_size * GEPA_REFLECTION_MINIBATCH_FRACTION)),
#       budget cap — see resolve_reflection_minibatch_size)
#
# - grow at ~20% of the (sampled) dataset so resolution scales with data;
# - floor of 5 keeps the previous behaviour for small datasets;
# - cap at dataset_size: a mini-batch cannot exceed the trainset;
# - cap by the metric-call budget so the run keeps at least
#   GEPA_MIN_REFLECTION_ITERATIONS reflection iterations (a large batch never
#   prevents reflection — the gepa engine does not gate iterations on the
#   remaining budget — it just burns the budget in fewer iterations).
# The env var remains an explicit operator override (used verbatim; validated
# at service startup — integer >= 1).
GEPA_REFLECTION_MINIBATCH_ENV = "OPTIMIZER_GEPA_REFLECTION_BATCH_SIZE"
GEPA_REFLECTION_MINIBATCH_MIN = 5
GEPA_REFLECTION_MINIBATCH_FRACTION = 0.2

# Each reflection iteration scores the current and the proposed candidate on
# the same mini-batch (gepa/proposer/reflective_mutation), costing ~2*b metric
# calls out of max_metric_calls = max_trials * n_samples. Guarantee at least
# this many iterations: below ~5 the reflective search degenerates into one or
# two mutation shots and GEPA's Pareto candidate selection has nothing to
# select over (it also mirrors the mini-batch floor of 5).
GEPA_MIN_REFLECTION_ITERATIONS = 5


def _read_reflection_minibatch_override() -> Optional[int]:
    """Parse the operator override env var; None when unset or blank.

    Raises ValueError naming the variable on a malformed value. Invoked once at
    import time so a bad deployment config fails at service startup instead of
    mid-optimization, and re-read per run so tests (and the rare live env
    change) see the current value.
    """
    raw = os.getenv(GEPA_REFLECTION_MINIBATCH_ENV)
    if raw is None or not raw.strip():
        return None
    stripped = raw.strip()
    try:
        value = int(stripped)
    except ValueError:
        # Truncate: the value is free text from the environment — keep the
        # error (and any log that carries it) bounded.
        raise ValueError(
            f"{GEPA_REFLECTION_MINIBATCH_ENV} must be an integer >= 1, "
            f"got {stripped[:32]!r}"
        ) from None
    if value < 1:
        raise ValueError(
            f"{GEPA_REFLECTION_MINIBATCH_ENV} must be an integer >= 1, "
            f"got {value}"
        )
    return value


def resolve_reflection_minibatch_size(dataset_size: int, max_trials: int) -> int:
    """Return the GEPA reflection mini-batch size for a run's dataset size."""
    override = _read_reflection_minibatch_override()
    if override is not None:
        return override
    scaled = max(
        GEPA_REFLECTION_MINIBATCH_MIN,
        math.ceil(dataset_size * GEPA_REFLECTION_MINIBATCH_FRACTION),
    )
    # The run's budget is max_metric_calls = max_trials * n_samples, and the
    # Studio always passes n_samples = dataset_size (both are the sampled item
    # count — see run_optimization), so the budget here is
    # max_trials * dataset_size. A reflection iteration costs ~2*b of it; cap b
    # so at least GEPA_MIN_REFLECTION_ITERATIONS iterations fit.
    budget_cap = (max_trials * dataset_size) // (2 * GEPA_MIN_REFLECTION_ITERATIONS)
    return max(1, min(dataset_size, scaled, budget_cap))


# Fail fast at service startup on a malformed operator override.
_read_reflection_minibatch_override()

