"""
Prompt strategy templates that provide structural diversity.
Each strategy represents a different prompt archetype/style.

This module helps avoid convergence by providing macro-level diversity
through different prompt structures and approaches.
"""

from typing import Dict, List
from dataclasses import dataclass
import random
import logging

logger = logging.getLogger(__name__)


@dataclass
class PromptStrategy:
    """Represents a prompt archetype/template with variables"""
    name: str
    template: str
    variables: List[str]  # Required variables like {input}, {context}
    description: str
    weight: float = 1.0  # Sampling weight


# Strategy Library, inspired by different prompting paradigms and frameworks
PROMPT_STRATEGIES = {
    "detailed_planner": PromptStrategy(
        name="detailed_planner",
        template="""You are a task assistant specializing in structured planning.

Given the following input, create a {plan_length} plan to address it:

Input: {input}
{context}

Your plan should:
1. Break down the task into clear steps
2. Consider all relevant information
3. Produce a {output_format} response

Plan length: {plan_length}
- short: 2-3 steps, concise analysis
- medium: 4-6 steps, moderate detail
- long: 7+ steps, comprehensive analysis

Response:""",
        variables=["input", "context", "output_format", "plan_length"],
        description="Task decomposition with variable detail levels",
        weight=1.2  # Slightly prefer this for complex tasks
    ),

    "expert_specialist": PromptStrategy(
        name="expert_specialist",
        template="""You are an expert specialist with deep domain knowledge.

Task: Analyze and respond to the following {task_type}

Input: {input}
{context}

Apply your expertise to:
- Identify key patterns and insights
- Consider domain-specific constraints
- Provide a {confidence_level} confidence answer

Response format: {output_format}
Confidence required: {confidence_level} (low/medium/high)

Analysis:""",
        variables=["input", "context", "task_type", "output_format", "confidence_level"],
        description="Domain expertise with confidence calibration",
        weight=1.0
    ),

    "chain_of_thought": PromptStrategy(
        name="chain_of_thought",
        template="""Let's solve this step by step with explicit reasoning.

Problem: {input}
{context}

Instructions:
1. First, identify what information we have
2. Next, determine what we need to find
3. Then, work through the solution logically
4. Finally, state your answer in {output_format} format

Think through each step carefully before responding.

Solution:""",
        variables=["input", "context", "output_format"],
        description="Step-by-step reasoning with explicit thought process",
        weight=1.1
    ),

    "constraint_focused": PromptStrategy(
        name="constraint_focused",
        template="""You are a precise assistant that follows constraints carefully.

Task: {input}
{context}

Critical constraints:
- Output format: {output_format}
- Scope: {scope} (narrow/balanced/broad)
- Requirements: Answer must be grounded in provided information

Steps:
1. Review all constraints
2. Identify relevant information
3. Formulate response within boundaries
4. Verify constraint compliance

Your response:""",
        variables=["input", "context", "output_format", "scope"],
        description="Constraint-aware processing with explicit verification",
        weight=0.9
    ),

    "iterative_refiner": PromptStrategy(
        name="iterative_refiner",
        template="""You are an iterative problem solver that refines answers.

Input: {input}
{context}

Process:
1. Initial understanding: What is being asked?
2. First pass: Quick answer attempt
3. Refinement: What could be improved?
4. Final answer: Refined {output_format} response

Apply this iterative thinking to produce a high-quality answer.

Response:""",
        variables=["input", "context", "output_format"],
        description="Multi-pass refinement approach",
        weight=0.8
    ),

    "minimal_direct": PromptStrategy(
        name="minimal_direct",
        template="""Answer concisely and directly.

{input}
{context}

Provide a {output_format} response. Be precise and focused.

Answer:""",
        variables=["input", "context", "output_format"],
        description="Minimal, direct approach",
        weight=0.7  # Lower weight - useful as baseline
    ),

    "example_driven": PromptStrategy(
        name="example_driven",
        template="""You are a pattern-matching assistant that learns from examples.

Task type: {task_type}
Input: {input}
{context}

Consider similar examples and patterns. Apply those insights to produce
a {output_format} response that follows best practices.

If multiple approaches exist, choose the most {reasoning_style} one.
Reasoning style: {reasoning_style} (conservative/balanced/creative)

Response:""",
        variables=["input", "context", "task_type", "output_format", "reasoning_style"],
        description="Example-based pattern matching",
        weight=1.0
    ),

    "structured_analyst": PromptStrategy(
        name="structured_analyst",
        template="""You are a systematic analyst who breaks down complex information.

Analysis Task: {input}
{context}

Systematic Analysis Framework:
1. Context Assessment: What information is available?
2. Key Points Identification: What are the critical elements?
3. Logical Structuring: How do these elements relate?
4. Conclusion Formation: What {output_format} answer emerges?

Depth: {analysis_depth} (surface/moderate/deep)

Provide your structured analysis:""",
        variables=["input", "context", "output_format", "analysis_depth"],
        description="Structured analytical approach",
        weight=1.0
    ),
}


# Variable instantiation options
VARIABLE_OPTIONS = {
    "plan_length": ["short", "medium", "long"],
    "confidence_level": ["low", "medium", "high"],
    "scope": ["narrow", "balanced", "broad"],
    "reasoning_style": ["conservative", "balanced", "creative"],
    "task_type": ["question", "analysis", "comparison", "extraction"],
    "output_format": ["concise phrase", "complete sentence", "structured response"],
    "analysis_depth": ["surface", "moderate", "deep"],
}


def instantiate_strategy(
    strategy: PromptStrategy,
    variable_values: Dict[str, str],
) -> str:
    """
    Fill in strategy template with concrete values.

    Args:
        strategy: The strategy template to instantiate
        variable_values: Dictionary of variable names to values

    Returns:
        Instantiated template string
    """
    template = strategy.template
    for var, value in variable_values.items():
        placeholder = "{" + var + "}"
        if placeholder in template:
            template = template.replace(placeholder, value)
    return template


def sample_strategy(exclude_recent: List[str] = None) -> tuple[PromptStrategy, Dict[str, str]]:
    """
    Sample a strategy with weighted random selection.

    Args:
        exclude_recent: List of strategy names to exclude (avoid repetition)

    Returns:
        Tuple of (strategy, instantiated_variables)
    """
    # Filter out recently used strategies to avoid repetition
    available = {
        k: v for k, v in PROMPT_STRATEGIES.items()
        if exclude_recent is None or k not in exclude_recent
    }

    if not available:
        # If all excluded, reset and use all strategies
        available = PROMPT_STRATEGIES

    # Weighted random selection
    strategies = list(available.values())
    weights = [s.weight for s in strategies]
    strategy = random.choices(strategies, weights=weights, k=1)[0]

    # Instantiate variables with random values from options
    var_values = {}
    for var in strategy.variables:
        if var in VARIABLE_OPTIONS:
            var_values[var] = random.choice(VARIABLE_OPTIONS[var])
        else:
            # Keep as placeholder for dataset-specific variables
            var_values[var] = "{" + var + "}"

    logger.debug(f"Sampled strategy: {strategy.name} with variables: {var_values}")

    return strategy, var_values


def get_strategy_by_name(name: str) -> PromptStrategy:
    """
    Get a specific strategy by name.

    Args:
        name: Name of the strategy

    Returns:
        PromptStrategy object

    Raises:
        KeyError: If strategy name not found
    """
    return PROMPT_STRATEGIES[name]


def list_available_strategies() -> List[str]:
    """
    Get list of all available strategy names.

    Returns:
        List of strategy names
    """
    return list(PROMPT_STRATEGIES.keys())
