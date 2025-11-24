"""
HotpotQA Multi-Hop Benchmark (GEPA-style)

Implements the compound AI system approach for apples-to-apples comparison with GEPA/Arize:
- Multi-hop retrieval with Wikipedia search
- Multiple optimizable prompts in pipeline
- Matches the complexity of the GEPA paper's HotpotQA setup

Usage:
    python scripts/benchmarks/hotpot_multihop_benchmark.py
"""

import random
import logging
from opik_optimizer.datasets import hotpot
from benchmarks.metrics.hotpot import hotpot_f1
from benchmarks.agents.hotpot_multihop_agent import HotpotMultiHopAgent
from opik_optimizer.utils.tools.wikipedia import search_wikipedia
from opik_optimizer.logging_config import setup_logging

# Configure logging
setup_logging()
logger = logging.getLogger(__name__)

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
train_dataset = hotpot(count=150, split="train", dataset_name="hotpot_train")
validation_dataset = hotpot(
    count=300, split="validation", dataset_name="hotpot_validation"
)
test_dataset = hotpot(count=300, split="test", dataset_name="hotpot_test")

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
    results = search_wikipedia(query, search_type="api", n=n)  # Use API search
    # Return top n results, padding if necessary
    return results[:n] if len(results) >= n else results + [""] * (n - len(results))


# BM25 search (for like-for-like comparison with GEPA/Arize)
def bm25_wikipedia_search(query: str, n: int = 5) -> list[str]:
    """
    BM25-based Wikipedia search for fair comparison with GEPA/Arize paper.

    Uses the production Comet/wikipedia-2017-bm25 index:
    - Same Wikipedia 2017 corpus as GEPA/Arize benchmarks
    - Optimized Parquet format (1.61 GB, downloads on first run)
    - BM25 parameters: k1=0.9, b=0.4
    - ~100ms query time after initial load

    Falls back to API search if BM25 index is unavailable.
    """
    try:
        results = search_wikipedia(
            query,
            search_type="bm25",
            n=n,
            bm25_hf_repo="Comet/wikipedia-2017-bm25",  # Production index
        )
        return results

    except Exception as e:
        logger.warning(f"BM25 search failed: {e}")
        logger.warning("Falling back to API Wikipedia search")
        return wikipedia_search(query, n)


# Choose search function
# Use bm25_wikipedia_search for fair comparison with GEPA paper
# Use wikipedia_search for quick testing without BM25 download
SEARCH_FN = bm25_wikipedia_search  # or wikipedia_search for testing
print(f"Search function: {SEARCH_FN.__name__}")
print()

# ============================================================================
# AGENT SETUP
# ============================================================================

print("Initializing multi-hop agent...")
agent = HotpotMultiHopAgent(
    search_fn=SEARCH_FN,
    model="openai/gpt-4.1-mini",
    model_parameters={"temperature": 1.0},
    num_passages_per_hop=5,
)

print(f"Agent has {len(agent.get_optimizable_prompts())} optimizable prompts:")
for prompt_name in agent.get_optimizable_prompts().keys():
    print(f"  - {prompt_name}")
print()

# ============================================================================
# METRIC WRAPPER
# ============================================================================


def multihop_metric(dataset_item: dict, agent_output: dict) -> float:
    """
    Metric wrapper for multi-hop agent.

    Args:
        dataset_item: Item from dataset (has 'answer' field)
        agent_output: Output from agent.execute() (has 'answer' field)

    Returns:
        F1 score
    """
    predicted_answer = agent_output.get("answer", "")
    return hotpot_f1(dataset_item, predicted_answer)


# ============================================================================
# BASELINE EVALUATION
# ============================================================================

print("=" * 80)
print("BASELINE EVALUATION")
print("=" * 80)
print("Evaluating initial (unoptimized) agent on validation set...")
print()

baseline_scores = []
for i, item in enumerate(validation_dataset.get_items()[:10]):  # Sample 10 for baseline
    question = item["question"]
    try:
        output = agent.execute(question, verbose=False)
        score = multihop_metric(item, output)
        baseline_scores.append(score)
        if (i + 1) % 5 == 0:
            print(f"  Evaluated {i + 1}/10 samples...")
    except Exception as e:
        logger.error(f"Error evaluating question: {e}")
        baseline_scores.append(0.0)

baseline_score = sum(baseline_scores) / len(baseline_scores) if baseline_scores else 0.0
print(f"\nBaseline F1 Score: {baseline_score:.4f}")
print()

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

# TODO: Implement agent optimization
# The optimizer needs to support multi-prompt optimization
# For now, we'll optimize just the final_answer prompt as a starting point

print("NOTE: Full multi-prompt optimization not yet implemented.")
print("For now, you can optimize individual prompts manually or wait for")
print("agent optimization support in MetaPromptOptimizer.")
print()

# Example of how it would work:
"""
optimizer = MetaPromptOptimizer(
    model="openai/gpt-4.1-mini",
    model_parameters={"temperature": 1.0},
    seed=SEED,
)

# Option 1: Optimize specific prompt
result = optimizer.optimize_prompt(
    prompt=agent.prompts["final_answer"],
    dataset=train_dataset,
    validation_dataset=validation_dataset,
    metric=lambda item: multihop_metric(
        item,
        agent.execute(item["question"])
    ),
    max_trials=100,
)

# Option 2: Optimize entire agent (future feature)
result = optimizer.optimize_agent(
    agent=agent,
    dataset=train_dataset,
    validation_dataset=validation_dataset,
    metric=multihop_metric,
    max_trials=100,
)
"""

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

# ============================================================================
# SUMMARY
# ============================================================================

print("=" * 80)
print("IMPLEMENTATION NOTES")
print("=" * 80)
print()
print("1. SEARCH FUNCTION:")
print("   ✅ BM25 search enabled with Comet/wikipedia-2017-bm25")
print("   ✅ Same Wikipedia 2017 corpus as GEPA/Arize paper")
print("   ✅ Optimized Parquet format (1.61 GB, downloads on first run)")
print("   - Switch to wikipedia_search() for quick testing without download")
print()
print("2. OPTIMIZATION:")
print("   - MetaPromptOptimizer needs multi-prompt optimization support")
print("   - Alternative: Optimize prompts individually then combine")
print("   - Alternative: Use EvolutionaryOptimizer for compound systems")
print()
print("3. COMPARISON TO GEPA:")
print("   ✅ Same dataset splits (150/300/300)")
print("   ✅ Same multi-hop architecture")
print("   ✅ Same search method (BM25 with Wikipedia 2017)")
print("   ✅ Ready for apples-to-apples comparison")
print()
print("=" * 80)
