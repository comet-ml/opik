"""
SDK-wide constants and small helpers.
"""

from __future__ import annotations

import os
import multiprocessing

# Environment keys.
OPIK_OPTIMIZER_NO_BANNER_ENV = "OPIK_OPTIMIZER_NO_BANNER"
OPIK_OPTIMIZATION_STUDIO_ENV = "OPIK_OPTIMIZATION_STUDIO"

# Display defaults.
DEFAULT_PANEL_WIDTH = 80
DEFAULT_DISPLAY_PREFIX = "| "

# Debug logging clip limits.
DEFAULT_DEBUG_TEXT_CLIP = 600
DEFAULT_TOOL_DEBUG_CLIP = 400
DEFAULT_TOOL_DEBUG_PREFIX = "tool: "

# Optimization result schema.
OPTIMIZATION_RESULT_SCHEMA_VERSION = "v1"

# Evaluation thread bounds.
MIN_EVAL_THREADS = 1
MAX_EVAL_THREADS = 32

# Generic defaults.
DEFAULT_NUM_THREADS = 12
DEFAULT_SEED = 42
DEFAULT_MODEL = "openai/gpt-5-nano"
DEFAULT_PERFECT_SCORE = 0.95
DEFAULT_SKIP_PERFECT_SCORE = True
DEFAULT_ENABLE_CONTEXT_LEARNING = True
DEFAULT_TOOL_CALL_MAX_ITERATIONS = 5
DEFAULT_N_SAMPLES_STRATEGY = "random_sorted"
DEFAULT_N_SAMPLES_MINIBATCH = None

# Feature flags
# TODO(opik-sdk): remove this flag once evaluate_on_dict_items is the default path.
ENABLE_EVALUATE_ON_DICT_ITEMS = False

# MetaPromptOptimizer defaults.
META_PROMPT_DEFAULT_PROMPTS_PER_ROUND = 4
META_PROMPT_DEFAULT_SYNTHESIS_PROMPTS_PER_ROUND = 2
META_PROMPT_DEFAULT_SYNTHESIS_START_ROUND = 3
META_PROMPT_DEFAULT_SYNTHESIS_ROUND_INTERVAL = 3
META_PROMPT_DEFAULT_NUM_TASK_EXAMPLES = 5
META_PROMPT_DEFAULT_HALL_OF_FAME_SIZE = 10
META_PROMPT_DEFAULT_HALL_OF_FAME_PATTERN_EXTRACTION_INTERVAL = 5
META_PROMPT_DEFAULT_HALL_OF_FAME_PATTERN_INJECTION_RATE = 0.6
META_PROMPT_DEFAULT_DATASET_CONTEXT_MAX_TOKENS = 10000
META_PROMPT_DEFAULT_DATASET_CONTEXT_RATIO = 0.25
META_PROMPT_DEFAULT_EXTRACT_METRIC_UNDERSTANDING = True
META_PROMPT_DEFAULT_ALLOW_USER_PROMPT_OPTIMIZATION = False

# MetaPromptOptimizer context fitting constants.
META_PROMPT_DEFAULT_MAX_VALUE_LENGTH = 2000
META_PROMPT_MIN_VALUE_LENGTH = 200
META_PROMPT_VALUE_LENGTH_REDUCTION_STEP = 200
META_PROMPT_DEFAULT_TOP_PROMPTS_PER_RECENT_ROUND = 4

# FewShotBayesianOptimizer defaults.
FEW_SHOT_DEFAULT_MIN_EXAMPLES = 2
FEW_SHOT_DEFAULT_MAX_EXAMPLES = 8
FEW_SHOT_DEFAULT_ENABLE_COLUMNAR_SELECTION = True
FEW_SHOT_DEFAULT_ENABLE_DIVERSITY = True
FEW_SHOT_DEFAULT_ENABLE_MULTIVARIATE_TPE = True
FEW_SHOT_DEFAULT_ENABLE_OPTUNA_PRUNING = True
FEW_SHOT_MAX_UNIQUE_COLUMN_VALUES = 25
FEW_SHOT_MAX_COLUMN_VALUE_LENGTH = 120
FEW_SHOT_MISSING_VALUE_SENTINEL = "<missing>"

# HierarchicalReflectiveOptimizer defaults.
HRO_DEFAULT_MAX_ITERATIONS = 5
HRO_DEFAULT_CONVERGENCE_THRESHOLD = 0.01
HRO_DEFAULT_MAX_PARALLEL_BATCHES = 5
HRO_DEFAULT_BATCH_SIZE = 25

# Parameter optimizer defaults.
PARAMETER_DEFAULT_N_TRIALS = 20
PARAMETER_DEFAULT_LOCAL_SEARCH_RATIO = 0.3
PARAMETER_DEFAULT_LOCAL_SEARCH_SCALE = 0.2

# Evolutionary optimizer defaults
EVOLUTIONARY_DEFAULT_POPULATION_SIZE = 30
EVOLUTIONARY_DEFAULT_NUM_GENERATIONS = 15
EVOLUTIONARY_DEFAULT_MUTATION_RATE = 0.2
EVOLUTIONARY_DEFAULT_CROSSOVER_RATE = 0.8
EVOLUTIONARY_DEFAULT_TOURNAMENT_SIZE = 4
EVOLUTIONARY_DEFAULT_HALL_OF_FAME_SIZE = 10
EVOLUTIONARY_DEFAULT_ELITISM_SIZE = 3
EVOLUTIONARY_DEFAULT_MIN_MUTATION_RATE = 0.1
EVOLUTIONARY_DEFAULT_MAX_MUTATION_RATE = 0.4
EVOLUTIONARY_DEFAULT_ADAPTIVE_MUTATION = True
EVOLUTIONARY_DEFAULT_DIVERSITY_THRESHOLD = 0.7
EVOLUTIONARY_DEFAULT_RESTART_THRESHOLD = 0.01
EVOLUTIONARY_DEFAULT_RESTART_GENERATIONS = 3
EVOLUTIONARY_DEFAULT_EARLY_STOPPING_GENERATIONS = 5
EVOLUTIONARY_DEFAULT_ENABLE_MOO = True
EVOLUTIONARY_DEFAULT_ENABLE_LLM_CROSSOVER = True
EVOLUTIONARY_DEFAULT_ENABLE_SEMANTIC_CROSSOVER = False
EVOLUTIONARY_DEFAULT_OUTPUT_STYLE_GUIDANCE = (
    "Produce clear, effective, and high-quality responses suitable for the task."
)
EVOLUTIONARY_DEFAULT_MOO_WEIGHTS = (1.0, -1.0)

# Optimizer short names for tagging.
OPTIMIZER_SHORT_NAMES = {
    "GepaOptimizer": "GEPA",
    "EvolutionaryOptimizer": "EVGO",
    "FewShotBayesianOptimizer": "FSBO",
    "HierarchicalReflectiveOptimizer": "HRPO",
    "MetaPromptOptimizer": "MEPO",
    "ParameterOptimizer": "PARAM",
}


def is_optimization_studio() -> bool:
    """Return True if running in optimization studio mode."""
    value = os.getenv(OPIK_OPTIMIZATION_STUDIO_ENV, "")
    return value.strip().lower() in {"1", "true", "yes"}


def resolve_project_name(project_name: str | None = None) -> str:
    """Resolve the Opik project name, favoring explicit arg then env."""
    if project_name:
        return project_name
    env_name = os.getenv("OPIK_PROJECT_NAME", "").strip()
    return env_name or "Optimization"


def tool_call_max_iterations() -> int:
    """Resolve the max tool-calling iterations from env or default."""
    raw = os.getenv("OPIK_TOOL_CALL_MAX_ITERATIONS", "").strip()
    if raw:
        try:
            return max(0, int(raw))
        except ValueError:
            return DEFAULT_TOOL_CALL_MAX_ITERATIONS
    return DEFAULT_TOOL_CALL_MAX_ITERATIONS


def normalize_eval_threads(n_threads: int | None) -> int:
    """Clamp evaluation threads to safe bounds."""
    if n_threads is None:
        cpu_count = multiprocessing.cpu_count() or MIN_EVAL_THREADS
        return max(MIN_EVAL_THREADS, min(cpu_count, MAX_EVAL_THREADS))
    try:
        value = int(n_threads)
    except (TypeError, ValueError):
        value = MIN_EVAL_THREADS
    return max(MIN_EVAL_THREADS, min(value, MAX_EVAL_THREADS))
