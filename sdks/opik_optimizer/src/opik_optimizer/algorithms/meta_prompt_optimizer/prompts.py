"""Prompt templates for the Meta-Prompt Optimizer.

This module contains all the prompt templates used by the optimizer for:
- Meta-reasoning about prompt improvements
- Generating candidate prompt variations
- MCP tool description optimization
"""

import textwrap

# Constants for variable delimiters
START_DELIM = "{"
END_DELIM = "}"

# System prompt for the meta-reasoning LLM that generates improved prompts
REASONING_SYSTEM_PROMPT = """You are an expert prompt engineer. Your task is to improve prompts for any type of task.

Focus on making the prompt more effective by:
1. Being clear and specific about what is expected
2. Providing necessary context and constraints
3. Guiding the model to produce the desired output format
4. Removing ambiguity and unnecessary elements
5. Maintaining conciseness while being complete

CRITICAL CONSTRAINTS (Anti-Data-Leakage):
1. DO NOT reference specific dataset field names (like 'answer', 'context', 'supporting_facts')
2. DO NOT reference specific metric names or evaluation methods (like 'F1 score', 'HotpotQA', 'token-level')
3. DO NOT include evaluation-specific terminology in the generated prompts
4. DO NOT mention dataset structure or internal implementation details
5. Focus on GENERAL task instructions that would work across different datasets of the same type

Variable Usage:
- Use {variable_name} syntax for variables that exist in the original prompt
- DO NOT add new variables that weren't in the original prompt
- DO NOT expose internal dataset structure through variable names
- The prompt should be generalizable to similar tasks

Instructions:
1. If there is a system prompt, prioritize adding instructions there if and only if it makes sense.
2. DO NOT add any variables or parameters to the prompt you are editing.
3. You can reuse variables that already exist in the prompt.
4. Ensure prompts would work on NEW, UNSEEN data of the same task type.

Return a JSON array of prompts with the following structure. Make sure to return a valid
JSON object with correct use of double quotes and single quotes. JSON keys should be
double-quoted:
{
    "prompts": [
        {
            "prompt": [{"role": "<role>", "content": "<content>"}],
            "improvement_focus": "what aspect this prompt improves",
            "reasoning": "why this improvement should help"
        },
        {
            "prompt": [{"role": "<role>", "content": "<content>"}],
            "improvement_focus": "what aspect this prompt improves",
            "reasoning": "why this improvement should help"
        }
    ]
}"""


# Meta-prompt template sections
META_PROMPT_SECTIONS = {
    "baseline_prompt": "###### START prompt ######\n{prompt}\n###### END prompt ######\n",
    "examples": "###### START example-data ######\n{examples}\n###### END example-data ######\n",
    "history": "###### START history ######\n{history}\n###### END history ######\n",
    "patterns": "###### START winning-patterns ######\n{patterns}\n###### END winning-patterns ######\n",
}


def build_candidate_generation_user_prompt(
    current_prompt_messages: str,
    best_score: float,
    history_context: str,
    task_context_str: str,
    analysis_instruction: str,
    metric_focus_instruction: str,
    improvement_point_1: str,
    prompts_per_round: int,
    pattern_guidance: str = "",  # NEW: Pattern injection support
) -> str:
    """Build the user prompt for generating candidate prompt variations.

    Args:
        current_prompt_messages: String representation of the current prompt messages
        best_score: Current best evaluation score
        history_context: Context from previous optimization rounds
        task_context_str: Task-specific context from dataset and metric
        analysis_instruction: Instruction for analyzing the prompt
        metric_focus_instruction: Instruction focusing on metric improvement
        improvement_point_1: First improvement guideline
        prompts_per_round: Number of prompts to generate
        pattern_guidance: Optional winning patterns to inject

    Returns:
        Formatted user prompt string
    """
    # Use structured sections for clarity
    prompt_section = META_PROMPT_SECTIONS["baseline_prompt"].format(
        prompt=current_prompt_messages
    )

    # Add pattern section if patterns are provided
    pattern_section = ""
    if pattern_guidance:
        pattern_section = f"\n{pattern_guidance}\n"

    return textwrap.dedent(
        f"""
            {prompt_section}

            Current score: {best_score}

            {META_PROMPT_SECTIONS["history"].format(history=history_context) if history_context else ""}

            {task_context_str}

            {pattern_section}

            {analysis_instruction}
            Generate [{prompts_per_round}] improved versions of this prompt.
            {metric_focus_instruction}

            Each version should aim to:
            {improvement_point_1}
            2. Provide necessary context and constraints (without relying on dataset-specific knowledge).
            3. Guide the model to produce the desired output format suitable for the task.
            4. Remove ambiguity and unnecessary elements.
            5. Maintain conciseness while being complete.
            {"6. Consider incorporating winning patterns where they improve clarity and effectiveness." if pattern_guidance else ""}

            REMEMBER: Do not mention specific dataset fields, metric names, or evaluation terminology.
            The prompt should be generalizable to similar tasks with different data.

            Return a valid JSON array as specified."""
    ).strip()


def build_mcp_tool_description_user_prompt(
    tool_name: str,
    current_description: str,
    tool_metadata_json: str,
    best_score: float,
    history_context: str,
    prompts_per_round: int,
) -> str:
    """Build the user prompt for generating improved MCP tool descriptions.

    Args:
        tool_name: Name of the tool being optimized
        current_description: Current tool description
        tool_metadata_json: JSON string of tool metadata
        best_score: Current best evaluation score
        history_context: Context from previous optimization rounds
        prompts_per_round: Number of descriptions to generate

    Returns:
        Formatted user prompt string
    """
    return textwrap.dedent(
        f"""
            Current tool name: {tool_name}
            Current tool description:
            ---
            {current_description}
            ---

            Tool metadata (JSON):
            {tool_metadata_json}

            Current best score: {best_score:.4f}
            {history_context}

            Generate [{prompts_per_round}] improved descriptions for this tool.
            Each description should clarify expected input arguments and set explicit expectations
            for how the tool output must be used in the final response.
            Avoid changing unrelated parts of the prompt. Focus only on the description text for `{tool_name}`.

            Return a JSON object of the form:
            {{
              "prompts": [
                {{
                  "tool_description": "...",
                  "improvement_focus": "...",
                  "reasoning": "..."
                }}
              ]
            }}
            """
    ).strip()


# System prompt for Hall of Fame pattern extraction
PATTERN_EXTRACTION_SYSTEM_PROMPT = """You are an expert at analyzing successful prompts and extracting reusable patterns.

Your goal is to identify what makes prompts effective at achieving high scores on specific metrics.
Focus on structural and stylistic elements that can be transferred to new prompts.

Be specific and actionable in your pattern descriptions.

CRITICAL: Do NOT mention specific dataset fields, metric names (like "F1 score", "HotpotQA"),
or evaluation-specific terminology. Focus on GENERAL prompt engineering principles."""


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
    return f"""
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
