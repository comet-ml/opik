"""Configuration for Optimization Studio."""

import math
import os

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

# GEPA reflection mini-batch sizing (OPIK-7511).
# GEPA only promotes a candidate to a full evaluation on a STRICT win over the
# reflection mini-batch (gepa/core/engine.py "new_sum <= old_sum: skip"). With
# the Studio's coarse 0/1 metrics (Equals, Levenshtein) a batch of b items can
# only take b+1 distinct sums, so a fixed b=5 discards most iterations for lack
# of resolution. Scale the batch with the dataset instead:
#
#   min(max_trials, dataset_size, max(GEPA_REFLECTION_MINIBATCH_MIN,
#       ceil(dataset_size * GEPA_REFLECTION_MINIBATCH_FRACTION)))
#
# - grow at ~20% of the (sampled) dataset so resolution scales with data;
# - floor of 5 keeps the previous behaviour for small datasets;
# - cap at max_trials: above it the SDK warns "GEPA reflection will not run"
#   (see _warn_if_reflection_minibatch_too_large in gepa_optimizer.py);
# - cap at dataset_size: a mini-batch cannot exceed the trainset.
# The env var remains an explicit operator override (used verbatim, min 1).
GEPA_REFLECTION_MINIBATCH_ENV = "OPTIMIZER_GEPA_REFLECTION_BATCH_SIZE"
GEPA_REFLECTION_MINIBATCH_MIN = 5
GEPA_REFLECTION_MINIBATCH_FRACTION = 0.2


def resolve_reflection_minibatch_size(dataset_size: int, max_trials: int) -> int:
    """Return the GEPA reflection mini-batch size for a run's dataset size."""
    override = os.getenv(GEPA_REFLECTION_MINIBATCH_ENV)
    if override is not None and override.strip():
        return max(1, int(override))
    scaled = max(
        GEPA_REFLECTION_MINIBATCH_MIN,
        math.ceil(dataset_size * GEPA_REFLECTION_MINIBATCH_FRACTION),
    )
    return max(1, min(max_trials, dataset_size, scaled))

