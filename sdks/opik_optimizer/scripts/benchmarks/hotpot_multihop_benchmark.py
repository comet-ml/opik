"""
HotpotQA Multi-Hop Benchmark (GEPA-style)

Implements the compound AI system approach for apples-to-apples comparison with GEPA/Arize:
- Multi-hop retrieval with Wikipedia search
- Multiple optimizable prompts in pipeline
- Matches the complexity of the GEPA paper's HotpotQA setup

Usage:
    python scripts/benchmarks/hotpot_multihop_benchmark.py
"""

import os
import random
import logging
from rich.console import Console
from rich.markdown import Markdown

from opik_optimizer.datasets import hotpot
from benchmarks.metrics.hotpot import hotpot_f1
from benchmarks.agents.hotpot_multihop_agent import HotpotMultiHopAgent
from opik_optimizer.utils.tools.wikipedia import search_wikipedia
from opik_optimizer.utils.llm_logger import LLMLogger
from opik_optimizer.logging_config import setup_logging
from opik_optimizer import MetaPromptOptimizer

# Configure logging
setup_logging()
logger = logging.getLogger(__name__)
tool_logger = LLMLogger("hotpot_multihop_benchmark", agent_name="Hotpot Multi-Hop")

# Set seed for reproducibility
SEED = 42
random.seed(SEED)

print("=" * 80)
print("HOTPOTQA MULTI-HOP BENCHMARK (GEPA-STYLE)")
print("=" * 80)
print()
print("This benchmark implements the compound AI system approach used in GEPA paper:")
print("- Multi-hop retrieval pipeline")
print("- Multiple optimizable prompts")
print("- Wikipedia search integration")
print()

# ============================================================================
# CONFIGURATION
# ============================================================================

# Dataset splits (matching GEPA paper: 150 train, 300 val, 300 test)
print("Loading datasets...")
train_dataset = hotpot(
    count=150, split="train", dataset_name="hotpot_train", test_mode=True
)
validation_dataset = hotpot(
    count=300, split="validation", dataset_name="hotpot_validation", test_mode=True
)
test_dataset = hotpot(
    count=300, split="test", dataset_name="hotpot_test", test_mode=True
)

print(f"  - Train: {len(train_dataset.get_items())} samples")
print(f"  - Validation: {len(validation_dataset.get_items())} samples")
print(f"  - Test: {len(test_dataset.get_items())} samples")
print()

# ============================================================================
# SEARCH FUNCTION SETUP
# ============================================================================


# Wrapper for Wikipedia search compatible with agent
def wikipedia_search(query: str, n: int = 5) -> list[str]:
    """
    Search Wikipedia and return top n passage texts.

    Args:
        query: Search query
        n: Number of passages to return

    Returns:
        List of passage texts
    """
    # If Wikipedia is disabled, return empty list without padding
    # Check environment variable at call time (not at module load time)
    disable_flag = os.getenv("OPIK_DISABLE_WIKIPEDIA", "").strip().lower()
    if disable_flag in ("1", "true", "yes", "on"):
        return []

    # Cap query length for logging; avoid model overrun
    if len(query) > 256:
        query = query[:256] + "..."

    with tool_logger.log_tool("wikipedia_search", query):
        try:
            results = search_wikipedia(
                query,
                search_type="bm25",
                k=n,
                bm25_hf_repo="Comet/wikipedia-2017-bm25",
            )
        except Exception:
            logger.exception("BM25 search failed, falling back to Wikipedia API")
            results = search_wikipedia(query, search_type="api", k=n)

    return results[:n] if len(results) >= n else results + [""] * (n - len(results))


# BM25 search (for like-for-like comparison)
def bm25_wikipedia_search(query: str, n: int = 5) -> list[str]:
    """
    BM25-based Wikipedia search for fair comparison.

    Uses the production Comet/wikipedia-2017-bm25 index:
    - Same Wikipedia 2017 corpus as benchmarks
    - Optimized Parquet format (1.61 GB, downloads on first run)
    - BM25 parameters: k1=0.9, b=0.4
    - ~100ms query time after initial load

    Falls back to API search if BM25 index is unavailable.
    """
    # If Wikipedia is disabled, return empty list
    # Check environment variable at call time (not at module load time)
    disable_flag = os.getenv("OPIK_DISABLE_WIKIPEDIA", "").strip().lower()
    if disable_flag in ("1", "true", "yes", "on"):
        return []

    try:
        with tool_logger.log_tool("wikipedia_bm25", query):
            results = search_wikipedia(
                query,
                search_type="bm25",
                k=n,
                bm25_hf_repo="Comet/wikipedia-2017-bm25",
            )
        return results

    except Exception as e:
        logger.warning(f"BM25 search failed (will fallback to API): {e}")
        return wikipedia_search(query, n)


# Choose search function
# FIXME: Before merging, switch back to bm25_wikipedia_search for fair comparison with GEPA paper
# Currently using API mode for testing (no BM25 index download required)
# Use bm25_wikipedia_search for fair comparison with GEPA paper
SEARCH_FN = bm25_wikipedia_search
print(f"Search function: {SEARCH_FN.__name__}")
print()

# ============================================================================
# AGENT SETUP
# ============================================================================

# Execution order for the multi-hop pipeline
AGENT_ORDER = [
    "create_query_1",
    "summarize_1",
    "create_query_2",
    "summarize_2",
    "final_answer",
]

MODEL_NAME = "openai/gpt-4.1-mini"
MODEL_PARAMS = {"temperature": 1.0}
NUM_PASSAGES = 5

print("Initializing multi-hop agent...")
agent = HotpotMultiHopAgent(
    search_fn=SEARCH_FN,
    model=MODEL_NAME,
    model_parameters=MODEL_PARAMS,
    num_passages_per_hop=NUM_PASSAGES,
    plan=AGENT_ORDER,
)

print(f"Agent has {len(agent.get_optimizable_prompts())} optimizable prompts:")
for prompt_name in agent.get_optimizable_prompts().keys():
    print(f"  - {prompt_name}")
print()
graph_def = agent.get_graph_definition()
print("Agent execution plan:")
print(f"  Plan order: {AGENT_ORDER}")
if isinstance(graph_def, dict) and graph_def.get("format") == "mermaid":
    print("  Mermaid graph:")
    graph_md = f"```mermaid\n{graph_def.get('data')}\n```"
    Console().print(Markdown(graph_md))
print()


def _ordered_prompts(prompts: dict) -> dict:
    """Ensure prompts follow the expected Hotpot step order."""
    ordered: dict = {}
    for name in AGENT_ORDER:
        if name in prompts:
            ordered[name] = prompts[name]
    for name, prompt in prompts.items():
        if name not in ordered:
            ordered[name] = prompt
    return ordered


def run_bundle_fn(
    bundle_prompts: dict, dataset_item: dict
) -> dict[str, str | dict[str, object]]:
    """
    Runner used by MetaPromptOptimizer for bundle evaluation.

    Instantiates a HotpotMultiHopAgent with the candidate prompts and executes the
    full multi-hop pipeline so metrics see the final answer plus trace.
    """
    ordered = _ordered_prompts(bundle_prompts)
    plan = [step for step in AGENT_ORDER if step in ordered]
    agent_runner = HotpotMultiHopAgent(
        search_fn=SEARCH_FN,
        model=MODEL_NAME,
        model_parameters=MODEL_PARAMS,
        num_passages_per_hop=NUM_PASSAGES,
        prompts=ordered,
        plan=plan,
    )
    return agent_runner.run(dataset_item)


# ============================================================================
# OPTIMIZATION
# ============================================================================

print("=" * 80)
print("OPTIMIZATION")
print("=" * 80)
print("This will optimize ALL prompts in the multi-hop pipeline:")
print("  - create_query_1")
print("  - summarize_1")
print("  - create_query_2")
print("  - summarize_2")
print("  - final_answer")
print()
print("Training on hotpot_train (150 samples)...")
print("Validation on hotpot_validation (300 samples)...")
print()

optimizer = MetaPromptOptimizer(
    model=MODEL_NAME,
    model_parameters=MODEL_PARAMS,
    seed=SEED,
)


def bundle_metric(item: dict, output: str, trace: dict | None = None) -> float:
    # trace carries intermediate hops if needed
    return hotpot_f1(item, output)


print("Running multi-prompt optimization (MetaPromptOptimizer)...")
opt_result = optimizer.optimize_prompt(
    prompt=agent.prompts,  # dict[str, ChatPrompt] triggers bundle mode
    dataset=train_dataset,
    metric=bundle_metric,
    candidate_generator_kwargs={
        "bundle_agent_class": HotpotMultiHopAgent,
        "bundle_plan": AGENT_ORDER,
        "bundle_agent_kwargs": {
            "search_fn": SEARCH_FN,
            "model": MODEL_NAME,
            "model_parameters": MODEL_PARAMS,
            "num_passages_per_hop": NUM_PASSAGES,
        },
    },
    max_trials=1,  # increase for more meta rounds; watch rollout budget
    # n_samples=None,
    n_samples=5,
)
print(f"Optimization best score: {opt_result.score:.4f}")
# opt_result.prompt holds best messages for single prompt; for bundle, use details if present
try:
    agent.prompts = opt_result.details.get("best_prompts", agent.prompts)  # type: ignore[assignment]
except Exception:
    pass

# ============================================================================
# TESTING
# ============================================================================

print("=" * 80)
print("TESTING")
print("=" * 80)
print("To test the optimized agent, run evaluation on test set:")
print()
print("test_scores = []")
print("for item in test_dataset.get_items():")
print("    output = agent.execute(item['question'])")
print("    score = multihop_metric(item, output)")
print("    test_scores.append(score)")
print("test_f1 = sum(test_scores) / len(test_scores)")
print("print(f'Test F1: {test_f1:.4f}')")
print()
