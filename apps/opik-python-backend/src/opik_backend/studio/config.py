"""Configuration for Optimization Studio."""

import os

# Opik Configuration
OPIK_URL = os.getenv("OPIK_URL_OVERRIDE")

# LLM API Keys
LLM_API_KEYS = {
    "OPENAI_API_KEY": os.getenv("OPENAI_API_KEY"),
    "ANTHROPIC_API_KEY": os.getenv("ANTHROPIC_API_KEY"),
    "OPENROUTER_API_KEY": os.getenv("OPENROUTER_API_KEY"),
}

# Metric Defaults
DEFAULT_REFERENCE_KEY = "answer"
DEFAULT_CASE_SENSITIVE = False

# Execution timeout for optimization jobs (default: 2 hours)
OPTIMIZATION_TIMEOUT_SECS = int(os.getenv("OPTSTUDIO_EXECUTION_TIMEOUT", "7200"))

# Dataset sampling (limits items used during optimization to prevent OOM)
DATASET_SAMPLES = int(os.getenv("OPTSTUDIO_DATASET_SAMPLES", "20"))

# Optimization Runtime Parameters
# These are passed to optimizer.optimize_prompt() for all optimizer types
OPTIMIZER_RUNTIME_PARAMS = {
    # Generic parameters (all optimizers)
    "max_trials": int(os.getenv("OPTIMIZER_MAX_TRIALS", "10")),
    "n_samples": DATASET_SAMPLES,
    
    # GEPA-specific parameters (ignored by other optimizers)
    "reflection_minibatch_size": int(os.getenv("OPTIMIZER_GEPA_REFLECTION_BATCH_SIZE", "5")),
    "candidate_selection_strategy": os.getenv("OPTIMIZER_GEPA_CANDIDATE_SELECTION", "pareto"),  # "pareto" or "best"
    
    # Hierarchical-specific parameters (ignored by other optimizers)
    "max_retries": int(os.getenv("OPTIMIZER_HIERARCHICAL_MAX_RETRIES", "2")),
}

