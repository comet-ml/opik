"""Prompt templates for the Meta-Prompt Optimizer.

This module contains all the prompt templates used by the optimizer for:
- Meta-reasoning about prompt improvements
- Generating candidate prompt variations
"""

import textwrap

# Constants for variable delimiters
START_DELIM = "{"
END_DELIM = "}"


def build_reasoning_system_prompt(
    allow_user_prompt_optimization: bool = True, mode: str = "single"
) -> str:
    """Build the system prompt for the meta-reasoning LLM that generates improved prompts.

    Args:
        allow_user_prompt_optimization: If True, allows modifying both system and user prompts.
                                       If False, only system prompt is modified.

    Returns:
        System prompt string for the reasoning LLM
    """
    return (
        textwrap.dedent(
            """You are an expert prompt engineer. Your task is to improve prompts for any type of task.

        Focus on making the prompt more effective by:
        1. Being clear and specific about what is expected
        2. Providing necessary context and constraints
        3. Guiding the model to produce the desired output format
        4. Removing ambiguity and unnecessary elements
        5. Maintaining conciseness while being complete

        CRITICAL CONSTRAINTS (Anti-Data-Leakage):
        1. DO NOT reference specific dataset field names from evaluation data (like 'answer', 'context', 'supporting_facts' from QA datasets)
        2. DO NOT reference specific metric names or evaluation methods (like 'F1 score', 'HotpotQA', 'token-level')
        3. DO NOT include evaluation-specific terminology in the generated prompts
        4. DO NOT mention dataset-specific structure or internal evaluation implementation details
        5. Focus on GENERAL task instructions that would work across different datasets of the same type

        IMPORTANT: Domain terminology is allowed. For coding tasks, terms like 'function', 'class', 'method', 'code', 'test case' are legitimate domain knowledge, not data leakage. For other tasks, use appropriate domain terminology.

        Variable Usage:
        - CRITICAL: Check the "Available input variables" section in the task context - you MUST include ALL necessary input variables in your prompts
        - Use {start}variable_name{end} syntax for variables (e.g., if task context shows {start}context{end}, {start}question{end} are available, USE BOTH)
        - If the task has multiple input fields (like {start}context{end}, {start}question{end}, {start}code{end}, etc.), ensure your prompt references ALL that are needed for the task
        - You MAY add new variables if they improve the prompt's effectiveness and are task-appropriate
        - DO NOT add variables that expose dataset-specific field names from evaluation data
        - Variables should represent task inputs/outputs, not internal dataset structure

        {bundle_constraints}

        {{mode_instruction}}

        Instructions:
        1. FIRST: Review "Available input variables" in task context - ensure your prompt uses ALL necessary input variables for the task
        2. If there is a system prompt, prioritize adding instructions there if and only if it makes sense.
        3. You MAY add new variables or parameters if they improve prompt effectiveness and are appropriate for the task domain.
        4. Ensure prompts would work on NEW, UNSEEN data of the same task type.

        Return a JSON array of prompts with the following structure. Make sure to return a valid
        JSON object with correct use of double quotes and single quotes. JSON keys should be
        double-quoted:
        {output_format}"""
        )
        .strip()
        .replace(
            "{{mode_instruction}}",
            (
                "OPTIMIZATION MODE: You can modify BOTH system and user prompts."
                if allow_user_prompt_optimization
                else "OPTIMIZATION MODE: You can ONLY modify the system prompt. The user prompt will remain unchanged from the original."
            ),
        )
        .replace("{start}", START_DELIM)
        .replace("{end}", END_DELIM)
        .replace(
            "{bundle_constraints}",
            (
                """
        Bundle mode:
        - You will be given multiple named agents (each with its own chat prompt).
        - Keep agent names unchanged.
        - Preserve placeholders, tools, and role ordering per agent.
        - Return updated prompts grouped as candidate bundles. Each candidate can include bundle-level improvement_focus and reasoning.
        - Try to propose multiple distinct candidate bundles (e.g., up to the requested count) if possible.
        - Each candidate must include every agent under "agents" with fields: name, messages, improvement_focus, reasoning.
        """
            )
            if mode == "bundle"
            else "",
        )
        .replace(
            "{output_format}",
            (
                """
        {
          "candidates": [
            {
              "bundle_improvement_focus": "what the whole bundle is trying to improve",
              "bundle_reasoning": "why these changes should help overall",
              "agents": [
                {
                  "name": "<agent_name>",
                  "messages": [{"role": "<role>", "content": "<content>"}],
                  "improvement_focus": "what you changed for this agent",
                  "reasoning": "why this should help this agent"
                }
              ]
            }
          ]
        }"""
            )
            if mode == "bundle"
            else """
        {
            "prompts": [
                {
                    "prompt": [{"role": "<role>", "content": "<content>"}],
                    "improvement_focus": "what aspect this prompt improves",
                    "reasoning": "why this improvement should help"
                }
            ]
        }""",
        )
    )


# Meta-prompt template sections
META_PROMPT_SECTIONS = {
    "baseline_prompt": "<prompt>\n{prompt}\n</prompt>\n",
    "examples": "<example-data>\n{examples}\n</example-data>\n",
    "history": "<history>\n{history}\n</history>\n",
    "patterns": "<winning-patterns>\n{patterns}\n</winning-patterns>\n",
    "agents": "<agents>\n{agents}\n</agents>\n",
}

# Template for agent bundle user prompt
AGENT_BUNDLE_USER_PROMPT_TEMPLATE = """###### START agents ######
{agent_blocks}
###### END agents ######

Current score: {best_score}

{history_section}

{examples_section}

{patterns_section}

{analysis_instruction}
Generate updated versions for ALL agents above (keep names the same).
Return between 1 and {prompts_per_round} candidate bundles in the JSON format below.
Each candidate bundle must include ALL agents under "agents" and may also include bundle_improvement_focus and bundle_reasoning.

{metric_focus_instruction}

Diversity guidance:
- Consider different structures (concise vs detailed) where helpful per agent.
- Tighten entity specificity and retrieval intent for search-style agents.
- Strengthen factual grounding and output-format compliance for synthesis agents.

IMPORTANT:
- Preserve every placeholder token (curly-braced variables).
- Preserve tool/function definitions and message ordering.
- Do NOT rename agents or swap their responsibilities.

Generate up to [{prompts_per_round}] distinct bundle candidates when you see multiple viable directions; otherwise return your single best bundle.

Output must be valid JSON with the top-level key "candidates" (or "agents" for a single bundle)."""

# Template for candidate generation user prompt
CANDIDATE_GENERATION_USER_PROMPT_TEMPLATE = """{prompt_section}

Current score: {best_score}

{history_section}

{task_context_section}

{pattern_section}

{analysis_instruction}
Generate [{prompts_per_round}] improved versions of this prompt.
{metric_focus_instruction}

CRITICAL: Study the Hall of Fame entries above, what made those prompts successful? How can you BUILD ON those winning elements while still exploring bold new directions?

Generate [{prompts_per_round}] DISTINCTLY DIFFERENT prompt variations that:

DIVERSITY DIMENSIONS (explore all of these):
- Length: from ultra-minimal (1 sentence) to in-depth comprehensive (multi-paragraph with reasoning steps)
- Structure: direct commands, numbered steps, narrative flow, question-based, example-driven
- Tone: formal instruction, conversational guidance, Socratic questioning, technical specification
- Approach: explicit constraints, implicit guidance, meta-cognitive prompting, role-playing

STRATEGIC GUIDANCE:
- Be more specific and clear about expectations based on the task
- Build on patterns from top-performing prompts in the Hall of Fame
- ADD structure and reasoning guidance (consider prompting frameworks: Situation→Task→Objective→Knowledge→Examples, and/or include role, style, format, job description, constraints)
- Try counter-intuitive approaches - sometimes the opposite of conventional wisdom works
- Experiment with being MORE specific in some versions and MORE abstract in others
- Combine winning elements in unexpected ways
{pattern_integration_note}

Don't follow a template or recipe. Genuinely innovate by:
- Taking successful elements from history and amplifying or inverting them
- Testing hypotheses about what might work better (e.g., "what if we focus on X instead of Y?")
- Pushing to extremes (ultra-terse vs ultra-detailed)
- Trying something completely different if the current approach has plateaued

IMPORTANT REMINDERS:
- CHECK THE "Available input variables" section above - your prompts MUST use ALL necessary input variables
- If you see variables like {{start}}context{{end}}, {{start}}question{{end}}, {{start}}code{{end}}, etc. listed, INCLUDE THEM in your prompts
- Avoid mentioning specific dataset fields, metric names, or evaluation terminology
- Keep prompts generalizable to similar tasks

Return a valid JSON array as specified."""


def build_candidate_generation_user_prompt(
    current_prompt_messages: str,
    best_score: float,
    history_context: str,
    task_context_str: str,
    analysis_instruction: str,
    metric_focus_instruction: str,
    prompts_per_round: int,
    pattern_guidance: str = "",
    mode: str = "single",
    agent_blocks: str | None = None,
) -> str:
    """Build the user prompt for generating candidate prompt variations.

    Args:
        current_prompt_messages: String representation of the current prompt messages
        best_score: Current best evaluation score
        history_context: Context from previous optimization rounds
        task_context_str: Task-specific context from dataset and metric
        analysis_instruction: Instruction for analyzing the prompt
        metric_focus_instruction: Instruction focusing on metric improvement
        prompts_per_round: Number of prompts to generate
        pattern_guidance: Optional winning patterns to inject

    Returns:
        Formatted user prompt string
    """
    # Use structured sections for clarity
    if mode == "bundle" and agent_blocks is not None:
        prompt_section = META_PROMPT_SECTIONS["agents"].format(agents=agent_blocks)
    else:
        prompt_section = META_PROMPT_SECTIONS["baseline_prompt"].format(
            prompt=current_prompt_messages
        )

    # Build conditional sections
    history_section = (
        META_PROMPT_SECTIONS["history"].format(history=history_context)
        if history_context
        else ""
    )

    pattern_section = ""
    if pattern_guidance:
        pattern_section = (
            f"\n{META_PROMPT_SECTIONS['patterns'].format(patterns=pattern_guidance)}\n"
        )

    task_context_section = ""
    if task_context_str:
        task_context_section = (
            f"\n{META_PROMPT_SECTIONS['examples'].format(examples=task_context_str)}\n"
        )

    pattern_integration_note = (
        "\n- Creatively integrate the winning patterns provided - adapt, don't just copy"
        if pattern_guidance
        else ""
    )

    # Use the template
    result = CANDIDATE_GENERATION_USER_PROMPT_TEMPLATE.format(
        prompt_section=prompt_section,
        best_score=best_score,
        history_section=history_section,
        task_context_section=task_context_section,
        pattern_section=pattern_section,
        analysis_instruction=analysis_instruction,
        prompts_per_round=prompts_per_round,
        metric_focus_instruction=metric_focus_instruction,
        pattern_integration_note=pattern_integration_note,
    )

    return result.replace("{start}", START_DELIM).replace("{end}", END_DELIM)


def build_pattern_extraction_system_prompt() -> str:
    """Build the system prompt for Hall of Fame pattern extraction.

    Returns:
        System prompt string for pattern extraction
    """
    return textwrap.dedent(
        """You are an expert at analyzing successful prompts and extracting reusable patterns.

        Your goal is to identify what makes prompts effective at achieving high scores on specific metrics.
        Focus on structural and stylistic elements that can be transferred to new prompts.

        Be specific and actionable in your pattern descriptions.

        CRITICAL: Do NOT mention specific dataset fields, metric names (like "F1 score", "HotpotQA"),
        or evaluation-specific terminology. Focus on GENERAL prompt engineering principles."""
    ).strip()


def build_pattern_extraction_user_prompt(
    top_prompts_scorecard: str,
    metric_name: str,
) -> str:
    """Build the user prompt for extracting patterns from winning prompts.

    Args:
        top_prompts_scorecard: Formatted string with top-performing prompts
        metric_name: Name of the metric being optimized

    Returns:
        Formatted user prompt string
    """
    return textwrap.dedent(
        f"""
        Analyze these high-performing prompts and extract GENERALIZABLE patterns that made them successful.

        Metric being optimized: {metric_name}

        Top Performing Prompts:
        {top_prompts_scorecard}

        Your task:
        1. Identify specific instruction patterns that appear in high-scoring prompts
        2. Recognize structural approaches that seem effective (e.g., "step-by-step", "constraint listing", "explicit format requirements")
        3. Note phrasing styles that correlate with success
        4. Extract 3-5 concrete patterns that could be reused

        IMPORTANT:
        - Focus on STRUCTURE and APPROACH, not dataset-specific content
        - Patterns should be transferable to similar tasks
        - Be specific enough to be actionable (e.g., "Start with explicit constraint listing" not "be clear")
        - DO NOT mention specific dataset fields, metric names, or evaluation details

        Return patterns as a JSON array:
        {{
          "patterns": [
            {{
              "pattern": "Brief description of pattern",
              "example": "Example phrasing or structure",
              "rationale": "Why this helps"
            }}
          ]
        }}
        """
    ).strip()


def build_synthesis_prompt(
    top_prompts_with_scores: list[tuple[list[dict[str, str]], float, str]],
    task_context_str: str,
    best_score: float,
    num_prompts: int = 2,
) -> str:
    """Build a synthesis prompt that combines insights from top performers.

    This is used every N rounds to create comprehensive prompts that combine
    the best elements from multiple high-performing prompts, preventing convergence
    to overly terse solutions.

    Args:
        top_prompts_with_scores: List of (prompt_messages, score, reasoning) tuples
        task_context_str: Task context with dataset examples
        best_score: Current best score
        num_prompts: Number of synthesis prompts to request

    Returns:
        Synthesis prompt for generating comprehensive combined prompts
    """
    # Build showcase of top performers with FULL prompts (no truncation)
    top_performers = ""
    for i, (prompt_msg, score, reasoning) in enumerate(top_prompts_with_scores, 1):
        improvement = ((score / best_score) - 1) * 100 if best_score > 0 else 0
        top_performers += (
            f"\n--- Top Performer #{i} | Score: {score:.4f} ({improvement:+.1f}%) ---\n"
        )

        # Format prompt messages cleanly - show FULL content without truncation
        top_performers += "Full Prompt Messages:\n"
        for msg in prompt_msg:
            role = msg.get("role", "unknown")
            content = msg.get("content", "")
            # Show complete content - no truncation for synthesis
            top_performers += f"  [{role.upper()}]: {content}\n"
        top_performers += "\n"

        if reasoning:
            top_performers += f"Why it worked: {reasoning}\n"

    return textwrap.dedent(
        f"""
        SYNTHESIS ROUND - Combining Best Elements
        ==========================================

        You've seen multiple high-performing prompts. Now it's time to synthesize their best elements
        into comprehensive, well-structured prompts that combine their strengths.

        Current Best Score: {best_score:.4f}

        TOP PERFORMING PROMPTS TO SYNTHESIZE:
        {top_performers}

        {META_PROMPT_SECTIONS["examples"].format(examples=task_context_str) if task_context_str else ""}

        YOUR TASK:
        Generate EXACTLY {num_prompts} COMPREHENSIVE prompts that:

        1. COMBINE the most effective elements from the top performers above
        2. CREATE a longer, more detailed prompt that doesn't sacrifice depth for brevity
        3. SYNTHESIZE their successful patterns into a cohesive whole
        4. ADD structure and reasoning guidance (consider prompting frameworks that include situation, task, objective, knowledge, examples, style, format, job description, role)
        5. BE THOROUGH - don't worry about length, focus on completeness and clarity

        SYNTHESIS STRATEGIES:
        - First prompt: Combine ALL winning patterns into one comprehensive super-prompt
        - Second prompt: Take a different synthesis approach (e.g., structured vs narrative, step-by-step vs holistic)
        - If multiple prompts use numbered steps, combine into a comprehensive step-by-step guide
        - If some are terse and some verbose, create a version that's verbose where it helps
        - Take the best constraints from each and combine them
        - Add reasoning guidance or examples where they appeared effective
        - Structure the information clearly (use sections, numbering, or frameworks)

        IMPORTANT: This is a SYNTHESIS round, not a diversity round. Focus on:
        - Creating EXACTLY {num_prompts} high-quality comprehensive prompts (REQUIRED)
        - Depth and thoroughness over brevity
        - Combining proven elements rather than experimenting with new approaches
        - Each prompt should be comprehensive and complete, not a variation

        CRITICAL - Return VALID JSON with EXACTLY {num_prompts} prompts in this EXACT structure:
        {{
            "prompts": [
                {{
                    "prompt": [{{"role": "system", "content": "...comprehensive combined prompt..."}}, {{"role": "user", "content": "..."}}],
                    "improvement_focus": "Synthesis of top performers",
                    "reasoning": "How this combines the best elements from the top prompts"
                }},
                {{
                    "prompt": [{{"role": "system", "content": "...second comprehensive prompt..."}}, {{"role": "user", "content": "..."}}],
                    "improvement_focus": "Alternative synthesis approach",
                    "reasoning": "How this takes a different synthesis strategy"
                }}
            ]
        }}

        IMPORTANT: Each prompt object MUST have "prompt", "improvement_focus", and "reasoning" fields. Do NOT mix array and object syntax.
        """
    ).strip()


def build_agent_bundle_reasoning_system_prompt(
    allow_user_prompt_optimization: bool = True,
) -> str:
    """
    System prompt for optimizing multiple named chat prompts at once.
    """
    # Thin wrapper to keep compatibility; delegates to build_reasoning_system_prompt
    return build_reasoning_system_prompt(
        allow_user_prompt_optimization=allow_user_prompt_optimization, mode="bundle"
    )


def build_agent_bundle_user_prompt(
    agent_blocks: str,
    best_score: float,
    history_context: str,
    task_context_str: str,
    analysis_instruction: str,
    metric_focus_instruction: str,
    prompts_per_round: int,
    pattern_guidance: str = "",
) -> str:
    """
    User prompt for generating improved prompts across multiple named agents.
    """
    # Build conditional sections
    history_section = (
        META_PROMPT_SECTIONS["history"].format(history=history_context)
        if history_context
        else ""
    )
    examples_section = (
        META_PROMPT_SECTIONS["examples"].format(examples=task_context_str)
        if task_context_str
        else ""
    )
    patterns_section = (
        META_PROMPT_SECTIONS["patterns"].format(patterns=pattern_guidance)
        if pattern_guidance
        else ""
    )

    # Use the template
    result = AGENT_BUNDLE_USER_PROMPT_TEMPLATE.format(
        agent_blocks=agent_blocks,
        best_score=best_score,
        history_section=history_section,
        examples_section=examples_section,
        patterns_section=patterns_section,
        analysis_instruction=analysis_instruction,
        prompts_per_round=prompts_per_round,
        metric_focus_instruction=metric_focus_instruction,
    )

    return result.replace("{start}", START_DELIM).replace("{end}", END_DELIM)
